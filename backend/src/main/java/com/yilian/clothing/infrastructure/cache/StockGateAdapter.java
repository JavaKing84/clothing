package com.yilian.clothing.infrastructure.cache;

import com.yilian.clothing.domain.port.StockAvailabilityPort;
import org.springframework.stereotype.Component;

/**
 * {@link StockAvailabilityPort} 的 Redis 实现：把"库存变了"翻译成"覆盖闸门值"。
 *
 * <p>放在基础设施层而不是领域层，是因为它讲的是 Redis 的方言（key 命名、TTL、DECRBY）。
 * 领域层只认识"可售余量"这个概念，不认识 Redis。</p>
 *
 * <p>异常一律吞掉：闸门是性能设施，挂了应当退化成纯数据库行锁，系统变慢但不会算错。
 * 反过来说，如果这里抛异常导致整个出库失败，就等于"缓存故障 → 无法收银"，
 * 那是把缓存当成了单点。</p>
 */
@Component
public class StockGateAdapter implements StockAvailabilityPort {

    private final StockGate stockGate;

    public StockGateAdapter(StockGate stockGate) {
        this.stockGate = stockGate;
    }

    @Override
    public void refresh(long warehouseId, long skuId, int quantity) {
        stockGate.reset(warehouseId, skuId, quantity);
    }
}
