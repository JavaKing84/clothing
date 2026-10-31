package com.yilian.clothing.domain.model;

/**
 * 分仓库存（聚合根）。
 *
 * <p>这是一个**可变对象**，与项目里大量 record 不同，原因是它承担"行锁内的状态迁移"：
 * {@code SELECT ... FOR UPDATE} 拿到它 → 校验 → 扣减 → 写回。
 * 如果做成不可变 record，每次扣减都要 new 一个对象，反而让人误以为"换个对象就安全了"，
 * 掩盖了"真正的安全来自行锁"这个事实。</p>
 *
 * <p>不变量（domain invariant）：
 * <ul>
 *   <li>quantity 可以为负，但只有显式 force 才允许（Q47 现场确实会卖超）</li>
 *   <li>available = quantity - lockedQty，永不为负（预占不能超过账面）</li>
 * </ul></p>
 */
public class Stock {

    private final Long id;
    private final Long skuId;
    private final Long warehouseId;
    private final int loadedQuantity;
    private int quantity;
    private int lockedQty;
    private final int safetyStock;
    private int version;

    public Stock(Long id, Long skuId, Long warehouseId, int quantity, int lockedQty, int safetyStock, int version) {
        this.id = id;
        this.skuId = skuId;
        this.warehouseId = warehouseId;
        this.loadedQuantity = quantity;
        this.quantity = quantity;
        this.lockedQty = lockedQty;
        this.safetyStock = safetyStock;
        this.version = version;
    }

    public Long id() {
        return id;
    }

    public Long skuId() {
        return skuId;
    }

    public Long warehouseId() {
        return warehouseId;
    }

    public int quantity() {
        return quantity;
    }

    public int lockedQty() {
        return lockedQty;
    }

    public int safetyStock() {
        return safetyStock;
    }

    public int version() {
        return version;
    }

    /**
     * 加载时的原始数量。写库时用它算增量（{@link #delta()}），
     * 让 SQL 写成 {@code quantity = quantity + ?} 而不是覆盖式赋值——
     * 覆盖式赋值要求"整个系统只有我一个人改这行"，那是纪律；增量写法是物理保证。
     */
    public int loadedQuantity() {
        return loadedQuantity;
    }

    /** 本次变更量（可正可负） */
    public int delta() {
        return quantity - loadedQuantity;
    }

    /** 可售量：账面减去 Redis 预扣在途。用于"仅剩 X 件"的展示口径。 */
    public int available() {
        return quantity - lockedQty;
    }

    public boolean enough(int qty) {
        return available() >= qty;
    }

    public boolean belowSafety() {
        return quantity <= safetyStock;
    }

    /**
     * 扣减。返回扣减前数量，调用方用于写流水的 before_qty。
     * 允许负库存在这里不拦——拦不拦是应用层策略（force 参数），领域层只负责算清楚。
     */
    public int deduct(int qty) {
        int before = quantity;
        this.quantity = quantity - qty;
        this.version = version + 1;
        return before;
    }

    public int restore(int qty) {
        int before = quantity;
        this.quantity = quantity + qty;
        this.version = version + 1;
        return before;
    }

    /** 盘点/调账：直接置为目标值。差异由调用方算并写流水。 */
    public int resetTo(int target) {
        int before = quantity;
        this.quantity = target;
        this.version = version + 1;
        return before;
    }
}
