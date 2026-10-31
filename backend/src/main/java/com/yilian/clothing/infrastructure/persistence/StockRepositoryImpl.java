package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.domain.model.Stock;
import com.yilian.clothing.domain.model.StockAlert;
import com.yilian.clothing.domain.model.StockMatrixRow;
import com.yilian.clothing.domain.repository.StockRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 库存仓储实现 —— 全项目唯一出现 {@code FOR UPDATE} 的地方。
 *
 * <p>为什么 {@code FOR UPDATE} 必须紧贴事务边界：它持有的是 InnoDB 行锁，
 * 事务提交/回滚才释放。如果把它放在没有事务的方法里（Spring 的自动提交模式），
 * 语句执行完锁就没了，等于没加锁——这是"以为加了锁其实没有"的最常见写错法。</p>
 */
@Repository
public class StockRepositoryImpl extends JdbcSupport implements StockRepository {

    private static final String COLS = "id, sku_id, warehouse_id, quantity, locked_qty, safety_stock, version";

    public StockRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Stock> lockForUpdate(long skuId, long warehouseId) {
        return queryOne("SELECT " + COLS + """
                         FROM inv_sku_stock
                         WHERE sku_id = ? AND warehouse_id = ? AND del_flag = 0
                         FOR UPDATE
                        """,
                (rs, i) -> new Stock(rs.getLong("id"), rs.getLong("sku_id"), rs.getLong("warehouse_id"),
                        rs.getInt("quantity"), rs.getInt("locked_qty"), rs.getInt("safety_stock"),
                        rs.getInt("version")),
                skuId, warehouseId);
    }

    @Override
    public Optional<Stock> find(long skuId, long warehouseId) {
        return queryOne("SELECT " + COLS + " FROM inv_sku_stock WHERE sku_id = ? AND warehouse_id = ? AND del_flag = 0",
                (rs, i) -> new Stock(rs.getLong("id"), rs.getLong("sku_id"), rs.getLong("warehouse_id"),
                        rs.getInt("quantity"), rs.getInt("locked_qty"), rs.getInt("safety_stock"),
                        rs.getInt("version")),
                skuId, warehouseId);
    }

    @Override
    public void update(Stock stock) {
        // 增量写 + 版本自增：即使有人绕过领域服务直接改库，也不会产生"读-改-写"的丢失更新窗口
        int rows = update("""
                        UPDATE inv_sku_stock
                        SET quantity = quantity + ?, locked_qty = ?, version = version + 1
                        WHERE id = ?
                        """,
                stock.delta(), stock.lockedQty(), stock.id());
        if (rows != 1) {
            throw new IllegalStateException("库存行更新失败，id=" + stock.id());
        }
    }

    @Override
    public void insertIfAbsent(long skuId, long warehouseId, int initQty, int safetyStock) {
        // ON DUPLICATE KEY UPDATE id = id 是"插不进去就什么都不做"的标准写法。
        // 不用 INSERT IGNORE：那会连带忽略"字段超长""非空约束"等真错误，把 bug 藏起来。
        update("""
                        INSERT INTO inv_sku_stock (sku_id, warehouse_id, quantity, safety_stock)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE id = id
                        """,
                skuId, warehouseId, initQty, safetyStock);
    }

    @Override
    public List<Stock> lockAllOrdered(Collection<Long> skuIds, long warehouseId) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<Long> sorted = skuIds.stream().distinct().sorted().toList();
        String placeholders = String.join(",", sorted.stream().map(x -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(warehouseId);
        args.addAll(sorted);
        return queryList("SELECT " + COLS + " FROM inv_sku_stock WHERE warehouse_id = ? AND sku_id IN ("
                        + placeholders + ") AND del_flag = 0 ORDER BY sku_id FOR UPDATE",
                (rs, i) -> new Stock(rs.getLong("id"), rs.getLong("sku_id"), rs.getLong("warehouse_id"),
                        rs.getInt("quantity"), rs.getInt("locked_qty"), rs.getInt("safety_stock"),
                        rs.getInt("version")),
                args.toArray());
    }

    @Override
    public List<StockMatrixRow> matrixRows(Long warehouseId, String keyword, Long supplierId,
                                           String season, String color, String category) {
        StringBuilder sql = new StringBuilder("""
                SELECT k.id AS sku_id, s.spu_no, s.spu_name, k.size, k.color,
                       s.category, s.season, st.quantity
                FROM inv_sku_stock st
                         JOIN cat_sku k ON k.id = st.sku_id AND k.del_flag = 0
                         JOIN cat_spu s ON s.id = k.spu_id AND s.del_flag = 0
                WHERE st.warehouse_id = ? AND st.del_flag = 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(warehouseId);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (s.spu_no LIKE ? OR s.spu_name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (supplierId != null) {
            sql.append(" AND s.supplier_id = ?");
            args.add(supplierId);
        }
        if (season != null && !season.isBlank()) {
            sql.append(" AND s.season = ?");
            args.add(season);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND s.category = ?");
            args.add(category);
        }
        if (color != null && !color.isBlank()) {
            sql.append(" AND k.color = ?");
            args.add(color);
        }
        // FIELD() 保证 S/M/L/XL 的业务顺序，而不是字典序（字典序会把 L 排到 M 前面）
        sql.append(" ORDER BY s.spu_no, FIELD(k.size, 'XS','S','M','L','XL','XXL','XXXL'), k.color");
        return queryList(sql.toString(),
                (rs, i) -> new StockMatrixRow(rs.getLong("sku_id"), rs.getString("spu_no"),
                        rs.getString("spu_name"), rs.getString("size"), rs.getString("color"),
                        rs.getString("category"), rs.getString("season"), rs.getInt("quantity")),
                args.toArray());
    }

    @Override
    public List<StockAlert> alerts(long warehouseId) {
        return queryList("""
                        SELECT k.id AS sku_id, s.spu_no, s.spu_name, k.size, k.color,
                               st.quantity, st.safety_stock
                        FROM inv_sku_stock st
                                 JOIN cat_sku k ON k.id = st.sku_id AND k.del_flag = 0
                                 JOIN cat_spu s ON s.id = k.spu_id AND s.del_flag = 0
                        WHERE st.warehouse_id = ? AND st.quantity <= st.safety_stock
                        ORDER BY st.quantity ASC, s.spu_no ASC
                        LIMIT 100
                        """,
                (rs, i) -> new StockAlert(rs.getLong("sku_id"), rs.getString("spu_no"),
                        rs.getString("spu_name"), rs.getString("size"), rs.getString("color"),
                        rs.getInt("quantity"), rs.getInt("safety_stock")),
                warehouseId);
    }

    @Override
    public long totalQuantity(long warehouseId) {
        return queryLong("SELECT COALESCE(SUM(quantity),0) FROM inv_sku_stock WHERE warehouse_id = ? AND del_flag = 0",
                warehouseId);
    }
}
