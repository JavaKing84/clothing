package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.LossCase;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.Stock;
import com.yilian.clothing.domain.model.StockFlow;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.FlowRepository;
import com.yilian.clothing.domain.repository.StockRepository;
import com.yilian.clothing.domain.repository.StocktakeRepository;
import com.yilian.clothing.domain.service.StockDomainService;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 挂账用例（Q48「标记实物找不到的差异，可挂账」）。
 *
 * <p>挂账的本质是**推迟决策**：货找不到，但不能立刻判定丢失
 * （可能被顾客拿到试衣间、可能压在别的款下面、可能上错货架）。
 * 因此挂账**不动账面库存**，只留一条待查记录，首页显示"有 N 件待查"。
 * 等找到了就关闭（不动账），确认丢了才核销（扣库存 + 写盘点流水）。</p>
 */
@Service
public class LossAppService {

    private final StocktakeRepository stocktakeRepository;
    private final CatalogRepository catalogRepository;
    private final StockRepository stockRepository;
    private final StockDomainService stockDomainService;
    private final FlowRepository flowRepository;
    private final BizNoGenerator bizNoGenerator;

    public LossAppService(StocktakeRepository stocktakeRepository, CatalogRepository catalogRepository,
                          StockRepository stockRepository, StockDomainService stockDomainService,
                          FlowRepository flowRepository, BizNoGenerator bizNoGenerator) {
        this.stocktakeRepository = stocktakeRepository;
        this.catalogRepository = catalogRepository;
        this.stockRepository = stockRepository;
        this.stockDomainService = stockDomainService;
        this.flowRepository = flowRepository;
        this.bizNoGenerator = bizNoGenerator;
    }

    @Transactional
    public String open(String barcode, long warehouseId, int qty, String reason) {
        Operator op = operator();
        SkuView sku = catalogRepository.findSkuByBarcode(barcode, true)
                .orElseThrow(() -> BizException.of(ResultCode.BARCODE_UNKNOWN).with("barcode", barcode));
        String caseNo = bizNoGenerator.lossNo();
        stocktakeRepository.insertLoss(caseNo, sku.id(), warehouseId, qty, reason, op.id(), op.nickname());
        return caseNo;
    }

    /**
     * 核销挂账。
     *
     * @param deduct true = 确认丢失，扣库存并写盘点流水；false = 货找到了，账不用动
     */
    @Transactional
    public void close(String caseNo, boolean deduct) {
        LossCase loss = stocktakeRepository.findLossByNo(caseNo)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "挂账单不存在"));
        if (!loss.open()) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL, "该挂账单已核销");
        }
        if (deduct) {
            Operator op = operator();
            SkuView sku = catalogRepository.findSkuById(loss.skuId(), true).orElseThrow();
            int[] beforeAfter = stockDomainService.resetTo(loss.skuId(), loss.warehouseId(),
                    Math.max(0, currentQty(loss) - loss.qty()));
            StockFlow flow = StockFlow.builder()
                    .flowNo(bizNoGenerator.flowNo())
                    .flowType(FlowType.STOCKTAKE)
                    .sku(sku)
                    .warehouseId(loss.warehouseId())
                    .qty(loss.qty(), FlowType.STOCKTAKE)
                    .signedQty(-loss.qty())
                    .beforeAfter(beforeAfter[0], beforeAfter[1])
                    .operator(op)
                    .bizNo(caseNo)
                    .remark("挂账核销（实物确认丢失）")
                    .build();
            flowRepository.append(flow);
        }
        stocktakeRepository.closeLoss(caseNo);
    }

    private int currentQty(LossCase loss) {
        // 必须读真实库存再算目标值：如果直接用 loss.qty 当目标值，会把库存改成"丢失件数"
        return stockRepository.find(loss.skuId(), loss.warehouseId())
                .map(Stock::quantity).orElse(0);
    }

    public List<LossCase> list(String status, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return stocktakeRepository.listLoss(status, s, (p - 1) * s);
    }

    private Operator operator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }
}
