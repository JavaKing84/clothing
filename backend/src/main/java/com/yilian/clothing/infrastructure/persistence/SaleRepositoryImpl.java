package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.domain.model.SaleOrder;
import com.yilian.clothing.domain.repository.SaleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 销售单仓储实现。 */
@Repository
public class SaleRepositoryImpl extends JdbcSupport implements SaleRepository {

    public SaleRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    private static final RowMapper<SaleOrder> HEADER = (rs, i) -> new SaleOrder(
            rs.getLong("id"), rs.getString("order_no"), rs.getLong("warehouse_id"),
            rs.getLong("operator_id"), rs.getString("operator_name"), rs.getString("status"),
            rs.getInt("total_qty"), rs.getBigDecimal("tag_amount"), rs.getBigDecimal("discount_amount"),
            rs.getBigDecimal("received_amount"), rs.getBigDecimal("cost_amount"), rs.getString("pay_type"),
            rs.getString("customer_name"), rs.getString("customer_phone"), rs.getString("remark"),
            rs.getTimestamp("checkout_time") == null ? null : rs.getTimestamp("checkout_time").toLocalDateTime(),
            rs.getTimestamp("create_time").toLocalDateTime(), List.of());

    private static final RowMapper<SaleOrder.Item> ITEM = (rs, i) -> new SaleOrder.Item(
            rs.getLong("id"), rs.getLong("sku_id"), rs.getLong("spu_id"), rs.getString("spu_no"),
            rs.getString("spu_name"), rs.getString("size"), rs.getString("color"), rs.getInt("qty"),
            rs.getBigDecimal("tag_price"), rs.getBigDecimal("actual_price"), rs.getBigDecimal("cost_price"),
            rs.getString("flow_no"));

    private static final String HEADER_SQL = """
            SELECT id, order_no, warehouse_id, operator_id, operator_name, status, total_qty,
                   tag_amount, discount_amount, received_amount, cost_amount, pay_type,
                   customer_name, customer_phone, remark, checkout_time, create_time
            FROM biz_sale_order
            """;

    private static final String ITEM_SQL = """
            SELECT id, sku_id, spu_id, spu_no, spu_name, size, color, qty, tag_price, actual_price,
                   cost_price, flow_no
            FROM biz_sale_order_item WHERE order_id = ? ORDER BY id
            """;

    private List<SaleOrder.Item> items(long orderId, boolean withCost) {
        List<SaleOrder.Item> items = queryList(ITEM_SQL, ITEM, orderId);
        if (withCost) {
            return items;
        }
        // 店员视图：成本价逐行抹掉（Q68）。售价与实收是店员必须知道的，不能抹。
        return items.stream().map(i -> new SaleOrder.Item(i.id(), i.skuId(), i.spuId(), i.spuNo(), i.spuName(),
                i.size(), i.color(), i.qty(), i.tagPrice(), i.actualPrice(), null, i.flowNo())).toList();
    }

    @Override
    public long insertOrder(String orderNo, long warehouseId, long operatorId, String operatorName, String remark) {
        return insert("""
                        INSERT INTO biz_sale_order (order_no, warehouse_id, operator_id, operator_name,
                                                    status, remark)
                        VALUES (?, ?, ?, ?, 'OPEN', ?)
                        """,
                orderNo, warehouseId, operatorId, operatorName, remark);
    }

    @Override
    public Optional<SaleOrder> findByNo(String orderNo, boolean withCost) {
        return queryOne(HEADER_SQL + " WHERE order_no = ?", HEADER, orderNo)
                .map(h -> new SaleOrder(h.id(), h.orderNo(), h.warehouseId(), h.operatorId(), h.operatorName(),
                        h.status(), h.totalQty(), h.tagAmount(), h.discountAmount(), h.receivedAmount(),
                        h.costAmount(), h.payType(), h.customerName(), h.customerPhone(), h.remark(),
                        h.checkoutTime(), h.createTime(), items(h.id(), withCost)));
    }

    @Override
    public Optional<SaleOrder> findById(long id, boolean withCost) {
        return queryOne(HEADER_SQL + " WHERE id = ?", HEADER, id)
                .map(h -> new SaleOrder(h.id(), h.orderNo(), h.warehouseId(), h.operatorId(), h.operatorName(),
                        h.status(), h.totalQty(), h.tagAmount(), h.discountAmount(), h.receivedAmount(),
                        h.costAmount(), h.payType(), h.customerName(), h.customerPhone(), h.remark(),
                        h.checkoutTime(), h.createTime(), items(h.id(), withCost)));
    }

    @Override
    public List<SaleOrder> list(LocalDateTime from, LocalDateTime to, Long operatorId, int limit, int offset) {
        StringBuilder sql = new StringBuilder(HEADER_SQL + " WHERE create_time >= ? AND create_time < ?");
        if (operatorId != null) {
            sql.append(" AND operator_id = ?");
            List<SaleOrder> rows = queryList(sql + " ORDER BY create_time DESC LIMIT ? OFFSET ?", HEADER,
                    from, to, operatorId, limit, offset);
            return rows;
        }
        return queryList(sql + " ORDER BY create_time DESC LIMIT ? OFFSET ?", HEADER, from, to, limit, offset);
    }

    @Override
    public long upsertItem(long orderId, long skuId, long spuId, String spuNo, String spuName, String size,
                           String color, int qty, BigDecimal tagPrice, BigDecimal costPrice, String flowNo) {
        // Q50：同单同码再扫一次 → qty + qty，不新增行。
        // 用 ON DUPLICATE KEY UPDATE 而不是"先查再插"：先查再插在并发下会双插，
        // 而唯一索引 uk_order_sku 让这句话天然幂等。
        update("""
                        INSERT INTO biz_sale_order_item (order_id, sku_id, spu_id, spu_no, spu_name, size, color,
                                                          qty, tag_price, actual_price, cost_price, flow_no)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE qty = qty + ?, actual_price = VALUES(actual_price), flow_no = ?
                        """,
                orderId, skuId, spuId, spuNo, spuName, size, color, qty, tagPrice, tagPrice, costPrice, flowNo,
                qty, flowNo);
        Long id = jdbc.queryForObject("SELECT id FROM biz_sale_order_item WHERE order_id = ? AND sku_id = ?",
                Long.class, orderId, skuId);
        return id == null ? 0L : id;
    }

    @Override
    public void updateItemQty(long itemId, int qty, String flowNo) {
        update("UPDATE biz_sale_order_item SET qty = ?, flow_no = ? WHERE id = ?", qty, flowNo, itemId);
    }

    @Override
    public void updateItemActualPrice(long itemId, BigDecimal actualPrice) {
        update("UPDATE biz_sale_order_item SET actual_price = ? WHERE id = ?", actualPrice, itemId);
    }

    @Override
    public Optional<Long> findOrderIdByItem(long itemId) {
        return queryOne("SELECT order_id FROM biz_sale_order_item WHERE id = ?", (rs, i) -> rs.getLong(1), itemId);
    }

    @Override
    public Optional<Integer> itemQty(long itemId) {
        return queryOne("SELECT qty FROM biz_sale_order_item WHERE id = ?", (rs, i) -> rs.getInt(1), itemId);
    }

    @Override
    public void deleteItem(long itemId) {
        update("DELETE FROM biz_sale_order_item WHERE id = ?", itemId);
    }

    @Override
    public void refreshTotals(long orderId) {
        update("""
                        UPDATE biz_sale_order o
                        SET o.total_qty   = (SELECT COALESCE(SUM(qty),0) FROM biz_sale_order_item WHERE order_id = o.id),
                            o.tag_amount  = (SELECT COALESCE(SUM(qty * tag_price),0) FROM biz_sale_order_item WHERE order_id = o.id),
                            o.cost_amount = (SELECT COALESCE(SUM(qty * cost_price),0) FROM biz_sale_order_item WHERE order_id = o.id)
                        WHERE o.id = ?
                        """, orderId);
    }

    @Override
    public void checkout(long orderId, BigDecimal discountAmount, BigDecimal receivedAmount, String payType,
                         String customerName, String customerPhone, String remark) {
        update("""
                        UPDATE biz_sale_order
                        SET status = 'PAID', discount_amount = ?, received_amount = ?, pay_type = ?,
                            customer_name = ?, customer_phone = ?, remark = ?, checkout_time = NOW(3)
                        WHERE id = ? AND status = 'OPEN'
                        """,
                discountAmount, receivedAmount, payType, customerName, customerPhone, remark, orderId);
    }

    @Override
    public void cancel(long orderId) {
        update("UPDATE biz_sale_order SET status = 'CANCELLED' WHERE id = ? AND status = 'OPEN'", orderId);
    }

    @Override
    public List<String> findExpiredOpenOrders(LocalDateTime before) {
        return queryList("SELECT order_no FROM biz_sale_order WHERE status = 'OPEN' AND create_time < ?",
                (rs, i) -> rs.getString(1), before);
    }

    @Override
    public Optional<BigDecimal> itemLineAmount(long orderId, long skuId) {
        return queryOne("SELECT qty * actual_price FROM biz_sale_order_item WHERE order_id = ? AND sku_id = ?",
                (rs, i) -> rs.getBigDecimal(1), orderId, skuId);
    }
}
