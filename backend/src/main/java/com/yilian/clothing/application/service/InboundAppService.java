package com.yilian.clothing.application.service;

import com.yilian.clothing.infrastructure.mq.RabbitConfig;
import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.InboundOrder;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.StockFlow;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.InboundRepository;
import com.yilian.clothing.domain.repository.SupportRepository;
import com.yilian.clothing.domain.service.StockDomainService;
import com.yilian.clothing.infrastructure.cache.StockCache;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 入库用例（Q18/Q39/Q40/Q41/Q42/Q43）。
 *
 * <p>两阶段设计：<b>DRAFT 只记账，不动库存</b>；<b>SUBMIT 才动库存</b>。
 * 为什么不能边扫边加库存：
 * <ul>
 *   <li>Q43 要"撤销上一件"——已经加到库存里的东西没法优雅撤销（要写冲正流水，越滚越乱）；</li>
 *   <li>拆箱核对时发现少件/错码，整单不入库比逐件回滚干净得多；</li>
 *   <li>进货单要打印给供货商签字，签字前的数量必须是"可改的"。</li>
 * </ul>
 * 这与销售单"加购即扣"（ADR-11）方向相反，因为两件事的物理现实不同：
 * 卖出去的货已经在顾客手里，进货的货还在箱子里。</p>
 */
@Service
public class InboundAppService {

    private final InboundRepository inboundRepository;
    private final CatalogRepository catalogRepository;
    private final StockDomainService stockDomainService;
    private final SupportRepository supportRepository;
    private final BizNoGenerator bizNoGenerator;
    private final StockCache stockCache;

    public InboundAppService(InboundRepository inboundRepository, CatalogRepository catalogRepository,
                             StockDomainService stockDomainService, SupportRepository supportRepository,
                             BizNoGenerator bizNoGenerator, StockCache stockCache) {
        this.inboundRepository = inboundRepository;
        this.catalogRepository = catalogRepository;
        this.stockDomainService = stockDomainService;
        this.supportRepository = supportRepository;
        this.bizNoGenerator = bizNoGenerator;
        this.stockCache = stockCache;
    }

    @Transactional
    public InboundOrder create(CreateCmd cmd) {
        Operator op = requireOperator();
        String orderNo = bizNoGenerator.inboundNo();
        long orderId = inboundRepository.insertHeader(orderNo, cmd.warehouseId(), cmd.supplierId(),
                op.id(), op.nickname(), cmd.remark());
        if (cmd.items() != null) {
            for (ItemCmd item : cmd.items()) {
                appendItem(orderId, item, false);
            }
        }
        inboundRepository.refreshTotals(orderId);
        return inboundRepository.findById(orderId).orElseThrow();
    }

    /**
     * 扫一件入库。
     *
     * @return duplicated=true 表示"本单已有这个码"，按 Q41 不做处理（数量不变）
     */
    @Transactional
    public AddResult addItem(String orderNo, String barcode, int qty, BigDecimal purchasePrice) {
        InboundOrder order = requireEditable(orderNo);
        boolean inserted = appendItem(order.id(),
                new ItemCmd(barcode, qty, purchasePrice), true);
        inboundRepository.refreshTotals(order.id());
        InboundOrder fresh = inboundRepository.findById(order.id()).orElseThrow();
        return new AddResult(orderNo, barcode, inserted, fresh.totalQty(), fresh.totalCost(),
                inserted ? "已加入" : "该件已在本单中，未重复计数");
    }

    private boolean appendItem(long orderId, ItemCmd item, boolean resolvePrice) {
        if (item.qty() <= 0) {
            throw BizException.of(ResultCode.PARAM_ERROR, "数量必须大于 0");
        }
        SkuView sku = catalogRepository.findSkuByBarcode(item.barcode(), true)
                .orElseThrow(() -> BizException.of(ResultCode.BARCODE_UNKNOWN)
                        .with("barcode", item.barcode()));
        BigDecimal price = item.purchasePrice() != null ? item.purchasePrice() : sku.purchasePrice();
        if (price == null) {
            price = BigDecimal.ZERO;
        }
        return inboundRepository.appendItem(orderId, item.barcode(), sku.id(), item.qty(), price);
    }

    /** Q43：撤销上一件。只允许在 DRAFT 阶段，已入库的单要撤得走流水冲正。 */
    @Transactional
    public UndoResult undoLast(String orderNo) {
        InboundOrder order = requireEditable(orderNo);
        String barcode = inboundRepository.deleteLastItem(order.id())
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "本单没有可撤销的明细"));
        inboundRepository.refreshTotals(order.id());
        InboundOrder fresh = inboundRepository.findById(order.id()).orElseThrow();
        return new UndoResult(orderNo, barcode, fresh.totalQty(), fresh.totalCost());
    }

    /**
     * 提交入库：这里是**唯一**真正加库存的地方。
     *
     * <p>{@code markSubmitted} 用条件更新（status='DRAFT'）来抢占状态：
     * 两个店员同时点"提交"时，只有一个能改成功，另一个直接抛 1003。
     * 这比"先查状态再更新"可靠——后者在并发下会双提交，库存加两遍。</p>
     */
    @Transactional
    public SubmitResult submit(String orderNo) {
        InboundOrder order = inboundRepository.findByNo(orderNo)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "入库单不存在"));
        if (!order.editable()) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL, "该入库单已提交，不能重复提交");
        }
        if (order.items().isEmpty()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "空单不能提交");
        }
        inboundRepository.markSubmitted(order.id());

        Operator op = requireOperator();
        List<StockFlow> flows = new java.util.ArrayList<>();
        for (InboundOrder.Item item : order.items()) {
            SkuView sku = catalogRepository.findSkuById(item.skuId(), true)
                    .orElseThrow(() -> BizException.of(ResultCode.SKU_NOT_FOUND));
            StockFlow flow = stockDomainService.move(StockDomainService.MoveCmd
                    .in(sku, order.warehouseId(), item.qty(), op, bizNoGenerator.flowNo())
                    .withBizNo(orderNo)
                    .withPrices(null, item.purchasePrice())
                    .withRemark("入库"));
            flows.add(flow);
            supportRepository.appendOutbox(flow.flowNo(), "STOCK_CHANGED",
                    RabbitConfig.EXCHANGE, "stock.changed",
                    "{\"type\":\"STOCK_IN\",\"flowNo\":\"" + flow.flowNo() + "\",\"skuId\":" + sku.id()
                            + ",\"qty\":" + item.qty() + "}");
        }
        stockCache.bumpGeneration();
        InboundOrder fresh = inboundRepository.findByNo(orderNo).orElseThrow();
        return new SubmitResult(orderNo, fresh.totalQty(), fresh.totalCost(), flows.size());
    }

    public InboundOrder detail(String orderNo) {
        return inboundRepository.findByNo(orderNo)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "入库单不存在"));
    }

    public List<InboundOrder> list(int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return inboundRepository.list(s, (p - 1) * s);
    }

    private InboundOrder requireEditable(String orderNo) {
        InboundOrder order = inboundRepository.findByNo(orderNo)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "入库单不存在"));
        if (!order.editable()) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL, "该入库单已提交，不能再改");
        }
        return order;
    }

    private Operator requireOperator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }

    public record CreateCmd(long warehouseId, long supplierId, String remark, List<ItemCmd> items) {
    }

    public record ItemCmd(String barcode, int qty, BigDecimal purchasePrice) {
    }

    public record AddResult(String orderNo, String barcode, boolean inserted, int totalQty,
                            BigDecimal totalCost, String msg) {
    }

    public record UndoResult(String orderNo, String barcode, int totalQty, BigDecimal totalCost) {
    }

    public record SubmitResult(String orderNo, int totalQty, BigDecimal totalCost, int flowCount) {
    }
}
