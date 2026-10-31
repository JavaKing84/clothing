package com.yilian.clothing.domain.port;

/**
 * 库存可用量对外通告端口（ADR-04 第 3 层的自我纠偏）。
 *
 * <h3>为什么这个接口定义在领域层</h3>
 * <p>领域层只允许改库存的那一个出口（{@code StockDomainService.move/resetTo}）。
 * 每次改成功，"Redis 里那份可售余量"就过期了，必须立刻覆盖回去，
 * 否则会出现真实现场踩到的怪事：<b>刚入库 10 件，扫码却提示仅剩 0 件</b>。</p>
 *
 * <p>但 Redis 是基础设施，领域层不能依赖它。所以领域层只声明"我需要通告一声"，
 * 由基础设施实现（{@code StockGateAdapter}）。这样：</p>
 * <ul>
 *   <li>五个改库存的入口（入库提交、退货、调拨、盘点、冲正）自动全覆盖——
 *       因为它们都走同一个领域出口，不需要每个 AppService 记得补一行 reset；</li>
 *   <li>领域层单测可以传一个 no-op 实现，不启动 Redis 也能测；</li>
 *   <li>Redis 故障时实现方自行吞掉异常（fail-open），领域逻辑不受影响。</li>
 * </ul>
 */
public interface StockAvailabilityPort {

    /** 用数据库的真实余量覆盖缓存。实现方必须自行保证"失败不抛异常"。 */
    void refresh(long warehouseId, long skuId, int quantity);

    /** 什么都不做的实现，供单元测试/无 Redis 环境使用。 */
    StockAvailabilityPort NOOP = (warehouseId, skuId, quantity) -> {
    };
}
