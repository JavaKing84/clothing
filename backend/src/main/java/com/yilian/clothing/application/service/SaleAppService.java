package com.yilian.clothing.application.service;

import com.yilian.clothing.infrastructure.mq.RabbitConfig;
import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.SaleOrder;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.Stock;
import com.yilian.clothing.domain.model.StockFlow;
import com.yilian.clothing.domain.repository.CatalogRepository;
import com.yilian.clothing.domain.repository.FlowRepository;
import com.yilian.clothing.domain.repository.SaleRepository;
import com.yilian.clothing.domain.repository.StockRepository;
import com.yilian.clothing.domain.repository.SupportRepository;
import com.yilian.clothing.domain.service.PricingService;
import com.yilian.clothing.domain.service.StockDomainService;
import com.yilian.clothing.infrastructure.cache.StockCache;
import com.yilian.clothing.infrastructure.cache.StockGate;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售用例（Q9/Q15/Q16/Q45~Q50）—— 全项目并发最激烈的路径。
 *
 * <h3>一次扫码出库的完整链路（每一步都为某个具体事故而存在）</h3>
 * <ol>
 *   <li><b>幂等检查</b>：同一 scanToken 已处理过 → 直接返回首次结果，不重复扣（Q76 的"手抖两次"）；</li>
 *   <li><b>Redis 闸门</b>：先挡掉明显不够的请求，避免 9 个人在数据库排队（Q47 的"仅剩 X 件"也靠它即时返回）；</li>
 *   <li><b>行锁 + 原子递减</b>：{@code SELECT ... FOR UPDATE} + {@code quantity = quantity - ?}（Q76 的"两人同时扫不覆盖"）；</li>
 *   <li><b>写流水</b>：与扣减同事务，账本与库存永远同生共死；</li>
 *   <li><b>回写闸门</b>：用数据库真实余量覆盖 Redis，防止两套数据漂移；</li>
 *   <li><b>发件箱</b>：负库存告警走 outbox → MQ，不阻塞收银（Q47 强制出库后的兜底通知）。</li>
 * </ol>
 *
 * <h3>为什么"加购即扣库存"</h3>
 * <p>顾客把衣服拿在手上那一刻，库存就该消失（ADR-11）。如果等结账才扣，
 * 另一个店员会把同一件卖给第二个人——这正是 Q8 说的"卖东西时候忘记是否还有库存"。</p>
 */
@Service
public class SaleAppService {

    private final SaleRepository saleRepository;
    private final CatalogRepository catalogRepository;
    private final StockRepository stockRepository;
    private final FlowRepository flowRepository;
    private final StockDomainService stockDomainService;
    private final SupportRepository supportRepository;
    private final StockGate stockGate;
    private final StockCache stockCache;
    private final BizNoGenerator bizNoGenerator;

    @Value("${app.stock.matrix-cache-seconds:60}")
    private long cacheSeconds;

    public SaleAppService(SaleRepository saleRepository, CatalogRepository catalogRepository,
                          StockRepository stockRepository, FlowRepository flowRepository,
                          StockDomainService stockDomainService, SupportRepository supportRepository,
                          StockGate stockGate, StockCache stockCache, BizNoGenerator bizNoGenerator) {
        this.saleRepository = saleRepository;
        this.catalogRepository = catalogRepository;
        this.stockRepository = stockRepository;
        this.flowRepository = flowRepository;
        this.stockDomainService = stockDomainService;
        this.supportRepository = supportRepository;
        this.stockGate = stockGate;
        this.stockCache = stockCache;
        this.bizNoGenerator = bizNoGenerator;
    }

    @Transactional
    public SaleOrder open(long warehouseId, String remark) {
        Operator op = requireOperator();
        String orderNo = bizNoGenerator.saleNo();
        long id = saleRepository.insertOrder(orderNo, warehouseId, op.id(), op.nickname(), remark);
        return saleRepository.findById(id, op.canSeeCost()).orElseThrow();
    }

    /**
     * 扫一件加入当前单（Q46「扫一件弹确认框确认后出库」：确认在前端，扣减在服务端）。
     */
    @Transactional
    public AddResult addItem(String orderNo, String barcode, int qty, String scanToken,
                             boolean force, String reason) {
        Operator op = requireOperator();
        if (qty <= 0) {
            throw BizException.of(ResultCode.PARAM_ERROR, "数量必须大于 0");
        }
        if (scanToken == null || scanToken.isBlank()) {
            // 没有幂等令牌就无法区分"手抖两次"和"真的要卖两件"，宁可拒绝也不猜
            throw BizException.of(ResultCode.TOKEN_MISSING);
        }

        // ---- 第 1 层：幂等。已处理过的令牌直接回放首次结果 ----
        var replayed = flowRepository.findByScanToken(scanToken);
        if (replayed.isPresent()) {
            StockFlow f = replayed.get();
            return new AddResult(orderNo, f.skuId(), f.spuNo(), f.spuName(), f.size(), f.color(),
                    f.quantity(), f.afterQty(), true, f.flowNo(), "该次扫码已处理，未重复扣减");
        }

        SaleOrder order = saleRepository.findByNo(orderNo, true)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "销售单不存在"));
        if (!order.open()) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL, "该单已结账或已取消");
        }
        SkuView sku = catalogRepository.findSkuByBarcode(barcode, true)
                .orElseThrow(() -> BizException.of(ResultCode.BARCODE_UNKNOWN).with("barcode", barcode));

        // ---- 第 2 层：Redis 闸门。只挡"明显不够"的请求，不承担正确性 ----
        stockGate.warmUp(order.warehouseId(), sku.id(),
                stockRepository.find(sku.id(), order.warehouseId()).map(Stock::quantity).orElse(0));
        if (!force) {
            StockGate.Result gate = stockGate.tryAcquire(order.warehouseId(), sku.id(), qty);
            if (gate.decision() == StockGate.Decision.SHORT) {
                // 闸门是"提示"，不是事实源。补货（入库/退货/调拨入/盘点调增）之后
                // 它可能仍停在旧的低值上，此时若直接拒绝，就会出现真实现场那句
                // "明明刚入库 10 件，扫码却提示仅剩 0 件"。
                // 所以 SHORT 必须回查数据库再裁决，并用真值把闸门纠偏一次。
                int truth = stockRepository.find(sku.id(), order.warehouseId())
                        .map(Stock::available).orElse(0);
                if (truth < qty) {
                    throw BizException.of(ResultCode.STOCK_SHORTAGE)
                            .with("remainQty", truth)
                            .with("spuNo", sku.spuNo())
                            .with("size", sku.size())
                            .with("color", sku.color());
                }
                stockGate.reset(order.warehouseId(), sku.id(), truth);
            }
        }

        StockFlow flow;
        // SAVEPOINT 包住"扣库存 + 写流水"：并发撞 uk_scan_token 时只回滚这一段，
        // 本请求的事务还能继续用（回放首单结果），而不是把整个请求打成 500。
        Object savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
        try {
            // ---- 第 3 层：行锁 + 原子递减（StockDomainService 内） ----
            flow = stockDomainService.move(StockDomainService.MoveCmd
                    .out(sku, order.warehouseId(), qty, op, bizNoGenerator.flowNo())
                    .withBizNo(orderNo)
                    .withScanToken(scanToken)
                    // 出库流水必须带上**本行吊牌金额**，否则报表的营业额恒为 0、
                    // 毛利恒为负（sale_price 为 null 时 SUM 得到 null→0，而成本仍在减）。
                    // 结账时的抹零/折扣不在这里改，而是补一条数量为 0 的 PRICE_ADJUST 流水，
                    // 于是 SUM(sale_price) over (2,7,8) 恰好等于实收，账自然平。
                    .withPrices(sku.tagPrice().multiply(BigDecimal.valueOf(qty)), sku.purchasePrice())
                    .withAllowNegative(force)
                    .withRemark(force ? "强制出库：" + (reason == null ? "" : reason) : null));

            saleRepository.upsertItem(order.id(), sku.id(), sku.spuId(), sku.spuNo(), sku.spuName(),
                    sku.size(), sku.color(), qty, sku.tagPrice(),
                    sku.purchasePrice() == null ? BigDecimal.ZERO : sku.purchasePrice(), flow.flowNo());
            saleRepository.refreshTotals(order.id());
        } catch (DuplicateKeyException dup) {
            // 同一 scanToken 的两个请求几乎同时到达：第 1 层的预查都扑空，都走到这里，
            // 唯一索引只放行一个，另一个撞 uk_scan_token。
            // 这不是错误，正是幂等设计在并发下的正常形态——回放首次结果即可。
            TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
            stockGate.refund(order.warehouseId(), sku.id(), qty);
            StockFlow first = flowRepository.findByScanTokenCurrent(scanToken).orElseThrow(() -> dup);
            return new AddResult(orderNo, first.skuId(), first.spuNo(), first.spuName(), first.size(),
                    first.color(), first.quantity(), first.afterQty(), true, first.flowNo(),
                    "该次扫码已被并发请求处理，未重复扣减");
        } catch (RuntimeException e) {
            // 事务会回滚，但 Redis 的预扣不会自动回滚，必须手动补回去，
            // 否则 Redis 里的余量会越用越少，最后出现"明明有货却提示仅剩 0 件"
            stockGate.refund(order.warehouseId(), sku.id(), qty);
            throw e;
        }

        // ---- 第 5 层：用数据库真实值覆盖闸门，自我纠偏 ----
        stockGate.reset(order.warehouseId(), sku.id(), flow.afterQty());
        stockCache.bumpGeneration();

        supportRepository.saveScanToken(scanToken, sku.id(),
                "{\"orderNo\":\"" + orderNo + "\",\"flowNo\":\"" + flow.flowNo() + "\",\"qty\":" + qty
                        + ",\"afterQty\":" + flow.afterQty() + "}",
                LocalDateTime.now().plusSeconds(600));

        if (flow.afterQty() < 0) {
            // 负库存是必须让店主知道的事实（Q47 的"挂账/强制"不是免罪符）
            supportRepository.appendOutbox("NEG-" + flow.flowNo(), "STOCK_NEGATIVE",
                    RabbitConfig.EXCHANGE, "stock.negative",
                    "{\"type\":\"STOCK_NEGATIVE\",\"spuNo\":\"" + sku.spuNo() + "\",\"size\":\"" + sku.size()
                            + "\",\"color\":\"" + sku.color() + "\",\"afterQty\":" + flow.afterQty()
                            + ",\"operator\":\"" + op.nickname() + "\",\"reason\":\""
                            + (reason == null ? "" : reason.replace("\"", "'")) + "\"}");
        }
        return new AddResult(orderNo, sku.id(), sku.spuNo(), sku.spuName(), sku.size(), sku.color(),
                qty, flow.afterQty(), false, flow.flowNo(), "已加入购物车");
    }

    /**
     * 移除明细（顾客放回）：逐条冲正该明细产生的流水，库存回补。
     *
     * <p>为什么不能"删掉明细再把数量加回去"：那样流水与库存就对不上了。
     * 必须一条一冲正，账本上要留下"这件加过又撤了"的痕迹。</p>
     */
    @Transactional
    public SaleOrder removeItem(String orderNo, long itemId) {
        Operator op = requireOperator();
        SaleOrder order = saleRepository.findByNo(orderNo, true)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "销售单不存在"));
        if (!order.open()) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL, "已结账的单请走退货流程");
        }
        SaleOrder.Item target = order.items().stream().filter(i -> i.id().equals(itemId)).findFirst()
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "明细不存在"));
        reverseSaleFlows(orderNo, target.skuId(), op, "移除明细");
        saleRepository.deleteItem(itemId);
        saleRepository.refreshTotals(order.id());
        stockCache.bumpGeneration();
        return saleRepository.findByNo(orderNo, op.canSeeCost()).orElseThrow();
    }

    /**
     * 结账（Q45/Q49）。
     *
     * <p>实收金额必须手输（Q49），因此会产生"吊牌合计 ≠ 实收"的差额。
     * 差额不修改已写好的出库流水（账本只追加），而是**补一条数量为 0 的价格调整流水**：
     * 既保证 {@code SUM(sale_price) = 实收}，又让折扣在流水里看得见、可审计、可冲正。</p>
     */
    @Transactional
    public SaleOrder checkout(String orderNo, CheckoutCmd cmd) {
        Operator op = requireOperator();
        SaleOrder order = saleRepository.findByNo(orderNo, true)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "销售单不存在"));
        if (!order.open()) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL, "该单已结账或已取消");
        }
        if (order.items().isEmpty()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "空单不能结账");
        }
        if (cmd.receivedAmount() == null) {
            throw BizException.of(ResultCode.PARAM_ERROR, "请填写实收金额");
        }

        BigDecimal tagTotal = PricingService.tagTotal(order.items());
        BigDecimal discount = cmd.discountAmount() != null
                ? cmd.discountAmount()
                : tagTotal.subtract(cmd.receivedAmount());

        // 折扣分摊到每件，销量排行才能算出真实毛利（Q59）
        List<SaleOrder.Item> priced = PricingService.allocateDiscount(order.items(), discount);
        for (SaleOrder.Item item : priced) {
            saleRepository.updateItemActualPrice(item.id(), item.actualPrice());
        }

        BigDecimal diff = cmd.receivedAmount().subtract(tagTotal);
        if (diff.signum() != 0) {
            StockFlow first = flowRepository.listActiveByBiz(orderNo).stream()
                    .filter(f -> f.flowType() == FlowType.SALE).findFirst()
                    .orElseThrow(() -> BizException.of(ResultCode.CONFLICT, "该单没有出库流水，无法结账"));
            SkuView anySku = catalogRepository.findSkuById(first.skuId(), true).orElseThrow();
            StockFlow adjust = StockFlow.builder()
                    .flowNo(bizNoGenerator.flowNo())
                    .flowType(FlowType.PRICE_ADJUST)
                    .sku(anySku)
                    .warehouseId(order.warehouseId())
                    .qty(0, FlowType.PRICE_ADJUST)
                    .beforeAfter(first.afterQty(), first.afterQty())
                    .prices(diff, null)
                    .payType(cmd.payType())
                    .operator(op)
                    .bizNo(orderNo)
                    .remark("整单折扣/抹零：" + diff.negate() + " 元")
                    .build();
            flowRepository.append(adjust);
        }

        saleRepository.checkout(order.id(), discount, cmd.receivedAmount(), cmd.payType(),
                cmd.customerName(), cmd.customerPhone(), cmd.remark());
        saleRepository.refreshTotals(order.id());

        SaleOrder paid = saleRepository.findByNo(orderNo, true).orElseThrow();
        supportRepository.appendOutbox(orderNo, "SALE_PAID", RabbitConfig.EXCHANGE, "sale.paid",
                buildSaleEvent(paid));
        stockCache.bumpGeneration();
        return saleRepository.findByNo(orderNo, op.canSeeCost()).orElseThrow();
    }

    private String buildSaleEvent(SaleOrder order) {
        StringBuilder items = new StringBuilder();
        for (SaleOrder.Item item : order.items()) {
            if (items.length() > 0) {
                items.append(",");
            }
            items.append("{\"skuId\":").append(item.skuId())
                    .append(",\"spuId\":").append(item.spuId())
                    .append(",\"qty\":").append(item.qty())
                    .append(",\"amount\":").append(item.lineActualAmount())
                    .append(",\"cost\":").append(item.lineCostAmount()).append("}");
        }
        return "{\"orderNo\":\"" + order.orderNo() + "\",\"statDate\":\"" + LocalDate.now()
                + "\",\"receivedAmount\":" + order.receivedAmount() + ",\"items\":[" + items + "]}";
    }

    /** 取消未结账的单（顾客走了）：逐条冲正 + 回补 */
    @Transactional
    public SaleOrder cancel(String orderNo, String reason) {
        Operator op = requireOperator();
        SaleOrder order = saleRepository.findByNo(orderNo, true)
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "销售单不存在"));
        if (!order.open()) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL, "只有未结账的单可以取消");
        }
        for (SaleOrder.Item item : order.items()) {
            reverseSaleFlows(orderNo, item.skuId(), op, reason == null ? "取消订单" : reason);
        }
        saleRepository.cancel(order.id());
        stockCache.bumpGeneration();
        return saleRepository.findByNo(orderNo, op.canSeeCost()).orElseThrow();
    }

    /** 超时未结账自动取消（ADR-11）。由定时任务调用，必须独立事务。 */
    @Transactional
    public int cancelExpired(int timeoutMinutes) {
        List<String> expired = saleRepository.findExpiredOpenOrders(
                LocalDateTime.now().minusMinutes(timeoutMinutes));
        int count = 0;
        for (String orderNo : expired) {
            try {
                cancel(orderNo, "超时未结账自动取消");
                count++;
            } catch (BizException ignored) {
                // 别人刚好结账了，跳过即可
            }
        }
        return count;
    }

    /**
     * 冲正某单某 SKU 的全部出库流水。
     *
     * <p>注意：这里用"逐条冲正"而不是"恢复了多少件"，
     * 因为一件衣服可能被扫了 3 次（qty=3 合并成一行），
     * 也可能是 3 条独立流水。只有按流水逐条反，才能保证账本与库存严格对称。</p>
     */
    private void reverseSaleFlows(String bizNo, long skuId, Operator op, String reason) {
        List<StockFlow> flows = flowRepository.listActiveByBiz(bizNo).stream()
                .filter(f -> f.skuId() == skuId && f.flowType() == FlowType.SALE)
                .toList();
        for (StockFlow origin : flows) {
            SkuView sku = catalogRepository.findSkuById(skuId, true).orElseThrow();
            stockDomainService.move(StockDomainService.MoveCmd
                    .in(sku, origin.warehouseId(), origin.quantity(), op, bizNoGenerator.flowNo())
                    .withType(FlowType.REVERSE, 1)
                    .withBizNo(bizNo)
                    .withRemark(reason + "（冲正 " + origin.flowNo() + "）"));
            flowRepository.markReversed(origin.flowNo());
        }
    }

    public SaleOrder detail(String orderNo) {
        Operator op = requireOperator();
        return saleRepository.findByNo(orderNo, op.canSeeCost())
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "销售单不存在"));
    }

    public List<SaleOrder> list(LocalDate date, int page, int size) {
        Operator op = requireOperator();
        LocalDate d = date == null ? LocalDate.now() : date;
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        // 店员只看自己的单（Q13「店员看得见自己的业绩」），店主看全部
        Long onlyOp = op.isOwner() ? null : op.id();
        return saleRepository.list(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), onlyOp, s, (p - 1) * s);
    }

    /**
     * 小票数据（Q64 打印）。把"渲染"留给前端 CSS（58mm 与 A4 两套 @media print），
     * 服务端只给结构化数据——这样换打印机不用改后端。
     */
    public Receipt receipt(String orderNo) {
        Operator op = requireOperator();
        SaleOrder order = saleRepository.findByNo(orderNo, op.canSeeCost())
                .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "销售单不存在"));
        List<ReceiptLine> lines = order.items().stream()
                .map(i -> new ReceiptLine(i.spuName(), i.spuNo(), i.size(), i.color(), i.qty(),
                        i.tagPrice(), i.actualPrice(), i.lineActualAmount()))
                .toList();
        return new Receipt(order.orderNo(), order.operatorName(), order.checkoutTime(), lines,
                order.totalQty(), order.tagAmount(), order.discountAmount(), order.receivedAmount(),
                order.payType(), order.customerName(), order.remark());
    }

    private Operator requireOperator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }

    public record AddResult(String orderNo, Long skuId, String spuNo, String spuName, String size,
                            String color, int qty, int remainQty, boolean duplicated, String flowNo, String msg) {
    }

    public record CheckoutCmd(BigDecimal receivedAmount, BigDecimal discountAmount, String payType,
                              String customerName, String customerPhone, String remark) {
    }

    public record ReceiptLine(String spuName, String spuNo, String size, String color, int qty,
                              BigDecimal tagPrice, BigDecimal actualPrice, BigDecimal lineAmount) {
    }

    public record Receipt(String orderNo, String operatorName, LocalDateTime checkoutTime,
                          List<ReceiptLine> lines, int totalQty, BigDecimal tagAmount,
                          BigDecimal discountAmount, BigDecimal receivedAmount, String payType,
                          String customerName, String remark) {
    }
}
