package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.Stock;
import com.yilian.clothing.domain.model.StockAlert;
import com.yilian.clothing.domain.model.StockMatrixRow;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 库存仓储端口。
 *
 * <p>{@link #lockForUpdate} 是整个并发方案的地基（ADR-04 第 1 层）。
 * 名字里带 lock 是故意的：让调用者一眼看到"这行是锁着的，事务别开太长"。</p>
 */
public interface StockRepository {

    /** SELECT ... FOR UPDATE。返回空表示该 SKU 在该仓还没有库存行（需要先建）。 */
    Optional<Stock> lockForUpdate(long skuId, long warehouseId);

    Optional<Stock> find(long skuId, long warehouseId);

    /** 悲观锁内的写回：quantity / locked_qty / version 一起更新。 */
    void update(Stock stock);

    /** 建库存行。并发建行靠唯一索引兜底（重复插入捕获 1062 后重查）。 */
    void insertIfAbsent(long skuId, long warehouseId, int initQty, int safetyStock);

    /** 按 sku_id 升序批量加锁，供多 SKU 场景（调拨/退货/结账后的整单操作）避免死锁。 */
    List<Stock> lockAllOrdered(Collection<Long> skuIds, long warehouseId);

    List<StockMatrixRow> matrixRows(Long warehouseId, String keyword, Long supplierId,
                                    String season, String color, String category);

    List<StockAlert> alerts(long warehouseId);

    /** 库存总件数，用于首页头图（Q12） */
    long totalQuantity(long warehouseId);
}
