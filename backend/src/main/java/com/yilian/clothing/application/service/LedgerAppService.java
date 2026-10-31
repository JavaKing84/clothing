package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.PageResult;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.StockFlow;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.FlowRepository;
import com.yilian.clothing.domain.service.StockDomainService;
import com.yilian.clothing.infrastructure.cache.StockCache;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 流水查询与冲正（Q60/Q61/Q62/Q69）。
 *
 * <h3>撤销为什么是"冲正"而不是"删除"</h3>
 * <p>账可以错，但不能改。原流水保留是复盘"谁在几点撤了什么"的唯一证据；
 * 冲正流水让 {@code SUM(signed_qty)} 仍然等于库存变化，对账不破。
 * 删除或改数字，等于把事故现场擦掉。</p>
 *
 * <h3>店员能撤什么</h3>
 * <p>Q69 说"店员能修改已录入的数据"，Q68 说"店员不能看进价利润"。
 * 平衡点是：店员只能撤**自己 24 小时内**的流水（{@code ledger:reverse:self}），
 * 店主可撤任意。这个限制写在代码里而不是配置里，因为它是"防内鬼"的边界，不该被随意改。</p>
 */
@Service
public class LedgerAppService {

    private final FlowRepository flowRepository;
    private final CatalogRepository catalogRepository;
    private final StockDomainService stockDomainService;
    private final BizNoGenerator bizNoGenerator;
    private final StockCache stockCache;

    public LedgerAppService(FlowRepository flowRepository, CatalogRepository catalogRepository,
                            StockDomainService stockDomainService, BizNoGenerator bizNoGenerator,
                            StockCache stockCache) {
        this.flowRepository = flowRepository;
        this.catalogRepository = catalogRepository;
        this.stockDomainService = stockDomainService;
        this.bizNoGenerator = bizNoGenerator;
        this.stockCache = stockCache;
    }

    public PageResult<StockFlow> query(Long warehouseId, FlowType type, String spuNo, Long operatorId,
                                       LocalDate from, LocalDate to, int page, int size) {
        Operator op = operator();
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        LocalDateTime start = from == null ? LocalDate.now().minusDays(30).atStartOfDay() : from.atStartOfDay();
        LocalDateTime end = (to == null ? LocalDate.now() : to).plusDays(1).atStartOfDay();
        // 店员看流水时只能看自己的（Q13「看得见自己的业绩」）；店主看全部
        Long onlyOp = op.isOwner() ? operatorId : op.id();
        List<StockFlow> rows = flowRepository.query(warehouseId, type, spuNo, onlyOp, start, end,
                s, (p - 1) * s, op.canSeeCost());
        long total = flowRepository.count(warehouseId, type, spuNo, onlyOp, start, end);
        return PageResult.of(rows, total, p, s);
    }

    @Transactional
    public ReverseResult reverse(String flowNo, String reason) {
        Operator op = operator();
        StockFlow origin = flowRepository.findByFlowNo(flowNo)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "流水不存在"));
        if (origin.status() == 2) {
            throw BizException.of(ResultCode.CONFLICT, "该流水已撤销，不能重复撤销");
        }
        boolean allowed = op.can(Permission.LEDGER_REVERSE)
                || (op.can(Permission.LEDGER_REVERSE_SELF)
                && origin.operatorId().equals(op.id())
                && origin.createTime().isAfter(LocalDateTime.now().minusHours(24)));
        if (!allowed) {
            throw BizException.of(ResultCode.FORBIDDEN, "只能撤销自己 24 小时内的流水");
        }
        if (origin.flowType() == FlowType.REVERSE) {
            throw BizException.of(ResultCode.CONFLICT, "冲正流水不能再被冲正");
        }

        String revertNo;
        if (origin.flowType() == FlowType.PRICE_ADJUST) {
            // 价格调整流水数量为 0，只反金额，不动库存
            StockFlow revert = StockFlow.builder()
                    .flowNo(bizNoGenerator.flowNo())
                    .flowType(FlowType.REVERSE)
                    .sku(anySku(origin))
                    .warehouseId(origin.warehouseId())
                    .qty(0, FlowType.REVERSE)
                    .signedQty(0)
                    .beforeAfter(origin.afterQty(), origin.afterQty())
                    .prices(origin.salePrice() == null ? null : origin.salePrice().negate(), null)
                    .operator(op)
                    .bizNo(origin.bizNo())
                    .revertFlowNo(origin.flowNo())
                    .remark("撤销价格调整：" + reason)
                    .build();
            flowRepository.append(revert);
            revertNo = revert.flowNo();
        } else {
            SkuView sku = catalogRepository.findSkuById(origin.skuId(), true)
                    .orElseThrow(() -> BizException.of(ResultCode.SKU_NOT_FOUND));
            // 原流水是出库（signed<0）→ 冲正加回；原流水是入库（signed>0）→ 冲正扣掉
            int direction = origin.signedQty() < 0 ? 1 : -1;
            StockFlow revert = stockDomainService.move(StockDomainService.MoveCmd
                    .in(sku, origin.warehouseId(), origin.quantity(), op, bizNoGenerator.flowNo())
                    .withType(FlowType.REVERSE, direction)
                    .withBizNo(origin.bizNo())
                    .withRemark("撤销 " + origin.flowNo() + "：" + reason));
            revertNo = revert.flowNo();
        }
        flowRepository.markReversed(flowNo);
        stockCache.bumpGeneration();
        return new ReverseResult(flowNo, revertNo, origin.quantity());
    }

    private SkuView anySku(StockFlow flow) {
        return catalogRepository.findSkuById(flow.skuId(), true)
                .orElseThrow(() -> BizException.of(ResultCode.SKU_NOT_FOUND));
    }

    private Operator operator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }

    public record ReverseResult(String flowNo, String revertFlowNo, int quantity) {
    }
}
