package com.yilian.clothing.domain.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.Stock;
import com.yilian.clothing.domain.model.StockFlow;
import com.yilian.clothing.domain.port.StockAvailabilityPort;
import com.yilian.clothing.domain.repository.FlowRepository;
import com.yilian.clothing.domain.repository.StockRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 库存领域服务 —— 本项目唯一允许改 {@code inv_sku_stock.quantity} 的地方。
 *
 * <p>把它做成"唯一入口"而不是让每个 AppService 各写一遍 UPDATE，是因为
 * 一旦有第二条路径改库存，账就再也对不平了。所有出入库、退货、调拨、冲正
 * 都必须经过 {@link #move(MoveCmd)}。</p>
 *
 * <h3>并发正确性的三道锁（ADR-04）</h3>
 * <ol>
 *   <li><b>行锁</b>：{@code SELECT ... FOR UPDATE}，同一 SKU 的并发在这里排队；</li>
 *   <li><b>原子算术</b>：写回用 {@code quantity = quantity - ?}，而不是把 Java 里算好的值覆盖进去；</li>
 *   <li><b>唯一索引</b>：{@code uk_scan_token} 让重复提交在存储层失败，而不是靠 if 判断。</li>
 * </ol>
 *
 * <p>为什么第 2 点不能省：即使拿了行锁，如果写的是 {@code SET quantity = ?}（Java 里算的值），
 * 一旦将来有人绕过这个服务直接读-改-写，丢失更新又会回来。原子算术让正确性附着在 SQL 上，
 * 而不是附着在"大家都记得调用这个服务"这种纪律上。</p>
 */
@Service
public class StockDomainService {

    private final StockRepository stockRepository;
    private final FlowRepository flowRepository;
    private final StockAvailabilityPort availability;

    public StockDomainService(StockRepository stockRepository, FlowRepository flowRepository,
                             StockAvailabilityPort availability) {
        this.stockRepository = stockRepository;
        this.flowRepository = flowRepository;
        this.availability = availability;
    }

    /**
     * 单 SKU 移动库存并写流水。必须在调用方的事务里执行（否则锁在方法返回时就释放了，
     * 流水与库存会出现"库存扣了、流水没写"的窗口）。
     */
    public StockFlow move(MoveCmd cmd) {
        Stock stock = lockOrCreate(cmd.sku().id(), cmd.warehouseId());
        int before;
        if (cmd.direction() < 0) {
            // Q47：不足时把"还剩几件"带回给前端，让操作人自己决定，而不是直接拒绝
            if (!cmd.allowNegative() && !stock.enough(cmd.qty())) {
                throw BizException.of(ResultCode.STOCK_SHORTAGE)
                        .with("remainQty", stock.available())
                        .with("skuId", cmd.sku().id())
                        .with("spuNo", cmd.sku().spuNo())
                        .with("size", cmd.sku().size())
                        .with("color", cmd.sku().color());
            }
            before = stock.deduct(cmd.qty());
        } else {
            before = stock.restore(cmd.qty());
        }
        stockRepository.update(stock);

        // 改库存与写流水必须在同一事务：库存动了而流水没写，等于账本缺页，永远对不平。
        StockFlow flow = appendFlow(cmd, before, stock.quantity());

        // 库存一变，Redis 里那份"可售余量"就过期了，立刻用真值覆盖。
        // 放在这个唯一出口而不是每个 AppService 里各补一行：入库/退货/调拨/盘点/冲正
        // 五条路径自动全覆盖，不会再漏——漏一次就是"明明有货却提示仅剩 0 件"。
        availability.refresh(cmd.warehouseId(), cmd.sku().id(), flow.afterQty());
        return flow;
    }

    /** 写流水。拆成独立方法是为了让"改库存"和"记账本"在代码上就分离，方便分别加断言与单测。 */
    private StockFlow appendFlow(MoveCmd cmd, int before, int after) {
        StockFlow flow = StockFlow.builder()
                .flowNo(cmd.flowNo())
                .flowType(cmd.type())
                .sku(cmd.sku())
                .warehouseId(cmd.warehouseId())
                .qty(cmd.qty(), cmd.type())
                .beforeAfter(before, after)
                .prices(cmd.salePrice(), cmd.purchasePrice())
                .payType(cmd.payType())
                .operator(cmd.operator())
                .bizNo(cmd.bizNo())
                .scanToken(cmd.scanToken())
                .clientToken(cmd.clientToken())
                .remark(cmd.remark())
                .createTime(cmd.createTime() == null ? LocalDateTime.now() : cmd.createTime())
                .build();
        long id = flowRepository.append(flow);
        return new StockFlow(id, flow.flowNo(), flow.flowType(), flow.skuId(), flow.spuId(), flow.spuNo(),
                flow.spuName(), flow.size(), flow.color(), flow.warehouseId(), flow.quantity(), flow.signedQty(),
                flow.beforeQty(), flow.afterQty(), flow.salePrice(), flow.purchasePrice(), flow.payType(),
                flow.operatorId(), flow.operatorName(), flow.bizNo(), flow.scanToken(), flow.clientToken(),
                flow.status(), flow.revertFlowNo(), flow.remark(), flow.createTime());
    }

    /**
     * 多 SKU 场景（调拨、退货、整单撤销）一次性加锁。
     *
     * <p>按 skuId 升序加锁是**反死锁**的关键：A 事务锁 3 再锁 5，B 事务锁 5 再锁 3，
     * 就会互相等成环。全序加锁把环路消灭在发生之前——这是数据库教科书里的经典结论，
     * 但在真实项目里经常被忽略，因为单 SKU 测试永远测不出来。</p>
     */
    public void lockAllSorted(Collection<Long> skuIds, long warehouseId) {
        List<Long> sorted = skuIds.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        for (Long skuId : sorted) {
            lockOrCreate(skuId, warehouseId);
        }
    }

    /** 盘点调账：把库存直接置为目标值，返回 (before, after) 供调用方写差异流水。 */
    public int[] resetTo(long skuId, long warehouseId, int target) {
        Stock stock = lockOrCreate(skuId, warehouseId);
        int before = stock.resetTo(target);
        stockRepository.update(stock);
        availability.refresh(warehouseId, skuId, stock.quantity());
        return new int[]{before, stock.quantity()};
    }

    private Stock lockOrCreate(long skuId, long warehouseId) {
        return stockRepository.lockForUpdate(skuId, warehouseId)
                .orElseGet(() -> {
                    // 第一次卖新品时库存行可能还不存在。并发建行靠唯一索引兜底：
                    // 两个线程同时 insert，一个成功一个撞 1062，撞的那个重查即可。
                    stockRepository.insertIfAbsent(skuId, warehouseId, 0, 1);
                    return stockRepository.lockForUpdate(skuId, warehouseId)
                            .orElseThrow(() -> BizException.of(ResultCode.STOCK_ROW_MISSING));
                });
    }

    /**
     * 库存移动命令。
     *
     * @param direction     +1 入库方向，-1 出库方向。**显式传入而不是从 type 推断**，
     *                      因为"冲正"的方向取决于原单，从类型推会推错。
     * @param allowNegative Q47 的"强制出库"：现场确实会卖超（货在店里但没入库），
     *                      允许但必须留痕。
     */
    public record MoveCmd(
            SkuView sku,
            long warehouseId,
            FlowType type,
            int qty,
            int direction,
            Operator operator,
            String flowNo,
            String bizNo,
            String scanToken,
            String clientToken,
            BigDecimal salePrice,
            BigDecimal purchasePrice,
            String payType,
            String remark,
            boolean allowNegative,
            LocalDateTime createTime
    ) {
        public static MoveCmd in(SkuView sku, long wh, int qty, Operator op, String flowNo) {
            return new MoveCmd(sku, wh, FlowType.IN, qty, 1, op, flowNo, null, null, null,
                    null, null, null, null, false, null);
        }

        public static MoveCmd out(SkuView sku, long wh, int qty, Operator op, String flowNo) {
            return new MoveCmd(sku, wh, FlowType.SALE, qty, -1, op, flowNo, null, null, null,
                    null, null, null, null, false, null);
        }

        public MoveCmd withBizNo(String v) {
            return new MoveCmd(sku, warehouseId, type, qty, direction, operator, flowNo, v, scanToken, clientToken,
                    salePrice, purchasePrice, payType, remark, allowNegative, createTime);
        }

        public MoveCmd withScanToken(String v) {
            return new MoveCmd(sku, warehouseId, type, qty, direction, operator, flowNo, bizNo, v, clientToken,
                    salePrice, purchasePrice, payType, remark, allowNegative, createTime);
        }

        public MoveCmd withPrices(BigDecimal sale, BigDecimal purchase) {
            return new MoveCmd(sku, warehouseId, type, qty, direction, operator, flowNo, bizNo, scanToken,
                    clientToken, sale, purchase, payType, remark, allowNegative, createTime);
        }

        public MoveCmd withPayType(String v) {
            return new MoveCmd(sku, warehouseId, type, qty, direction, operator, flowNo, bizNo, scanToken,
                    clientToken, salePrice, purchasePrice, v, remark, allowNegative, createTime);
        }

        public MoveCmd withRemark(String v) {
            return new MoveCmd(sku, warehouseId, type, qty, direction, operator, flowNo, bizNo, scanToken,
                    clientToken, salePrice, purchasePrice, payType, v, allowNegative, createTime);
        }

        public MoveCmd withAllowNegative(boolean v) {
            return new MoveCmd(sku, warehouseId, type, qty, direction, operator, flowNo, bizNo, scanToken,
                    clientToken, salePrice, purchasePrice, payType, remark, v, createTime);
        }

        public MoveCmd withType(FlowType t, int dir) {
            return new MoveCmd(sku, warehouseId, t, qty, dir, operator, flowNo, bizNo, scanToken,
                    clientToken, salePrice, purchasePrice, payType, remark, allowNegative, createTime);
        }

        public MoveCmd withClientToken(String v) {
            return new MoveCmd(sku, warehouseId, type, qty, direction, operator, flowNo, bizNo, scanToken,
                    v, salePrice, purchasePrice, payType, remark, allowNegative, createTime);
        }

        public MoveCmd withCreateTime(LocalDateTime v) {
            return new MoveCmd(sku, warehouseId, type, qty, direction, operator, flowNo, bizNo, scanToken,
                    clientToken, salePrice, purchasePrice, payType, remark, allowNegative, v);
        }
    }
}
