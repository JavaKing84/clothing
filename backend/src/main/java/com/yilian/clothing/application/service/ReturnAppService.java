package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.OrderRows;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.OrderAuxRepository;
import com.yilian.clothing.domain.service.StockDomainService;
import com.yilian.clothing.infrastructure.cache.StockCache;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退货用例（Q17）。
 *
 * <p>退货在账上必须是**负收入**，而不是"把原来那笔删掉"。
 * 所以这里写 {@code FlowType.RETURN} 流水：{@code signed_qty = +qty}（货回架上了），
 * {@code sale_price = -退款额}（钱退出去了）。这样 {@code SUM(sale_price)} 自然就是净营业额，
 * 报表不需要任何"扣减退货"的特殊逻辑。</p>
 */
@Service
public class ReturnAppService {

    private final OrderAuxRepository orderAuxRepository;
    private final CatalogRepository catalogRepository;
    private final StockDomainService stockDomainService;
    private final BizNoGenerator bizNoGenerator;
    private final StockCache stockCache;

    public ReturnAppService(OrderAuxRepository orderAuxRepository, CatalogRepository catalogRepository,
                            StockDomainService stockDomainService, BizNoGenerator bizNoGenerator,
                            StockCache stockCache) {
        this.orderAuxRepository = orderAuxRepository;
        this.catalogRepository = catalogRepository;
        this.stockDomainService = stockDomainService;
        this.bizNoGenerator = bizNoGenerator;
        this.stockCache = stockCache;
    }

    @Transactional
    public String create(Cmd cmd) {
        Operator op = operator();
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "退货明细不能为空");
        }
        String orderNo = bizNoGenerator.returnNo();
        // 先算总额再插单头：避免"先插 0 再 UPDATE"的两步写入，
        // 中间态在被并发读到时会显示"退货 0 件"
        int totalQty = cmd.items().stream().mapToInt(Item::qty).sum();
        BigDecimal totalRefund = cmd.items().stream()
                .map(i -> i.refundAmount().multiply(BigDecimal.valueOf(i.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long returnId = orderAuxRepository.insertReturn(orderNo, cmd.saleOrderNo(), cmd.warehouseId(),
                cmd.returnType() == null ? "RETURN" : cmd.returnType(), totalQty, totalRefund,
                cmd.restock(), cmd.reason(), op.id(), op.nickname());

        for (Item item : cmd.items()) {
            SkuView sku = catalogRepository.findSkuByBarcode(item.barcode(), true)
                    .orElseThrow(() -> BizException.of(ResultCode.BARCODE_UNKNOWN)
                            .with("barcode", item.barcode()));
            String flowNo = null;
            if (cmd.restock()) {
                // restock=false 表示"退货但不回架"（比如拆了吊牌、有污渍），直接报损
                var flow = stockDomainService.move(StockDomainService.MoveCmd
                        .in(sku, cmd.warehouseId(), item.qty(), op, bizNoGenerator.flowNo())
                        .withType(FlowType.RETURN, 1)
                        .withBizNo(orderNo)
                        // 退货是**负收入**：流水金额取退款额的相反数，并乘以件数。
                        // 只乘 1 会让退 2 件的订单金额少算一半；不取负号会让退货变成营业额
                        .withPrices(item.refundAmount().negate().multiply(BigDecimal.valueOf(item.qty())),
                                sku.purchasePrice())
                        .withRemark("退货入库"));
                flowNo = flow.flowNo();
            }
            orderAuxRepository.insertReturnItem(returnId, sku.id(), item.qty(),
                    item.refundAmount().multiply(BigDecimal.valueOf(item.qty())), flowNo);
        }
        stockCache.bumpGeneration();
        return orderNo;
    }

    public List<OrderRows.ReturnRow> list(int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return orderAuxRepository.listReturns(s, (p - 1) * s);
    }

    private Operator operator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }

    public record Cmd(String saleOrderNo, long warehouseId, String returnType, boolean restock,
                      String reason, List<Item> items) {
    }

    public record Item(String barcode, int qty, BigDecimal refundAmount) {
    }
}
