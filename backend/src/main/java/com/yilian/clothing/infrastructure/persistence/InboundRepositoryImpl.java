package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.domain.model.InboundOrder;
import com.yilian.clothing.domain.repository.InboundRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 入库单仓储实现。 */
@Repository
public class InboundRepositoryImpl extends JdbcSupport implements InboundRepository {

    public InboundRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    private static final RowMapper<InboundOrder> HEADER = (rs, i) -> new InboundOrder(
            rs.getLong("id"), rs.getString("order_no"), rs.getLong("warehouse_id"),
            rs.getLong("supplier_id"), rs.getString("supplier_name"),
            rs.getLong("operator_id"), rs.getString("operator_name"),
            rs.getString("status"), rs.getInt("total_qty"), rs.getBigDecimal("total_cost"),
            rs.getString("remark"),
            rs.getTimestamp("submit_time") == null ? null : rs.getTimestamp("submit_time").toLocalDateTime(),
            rs.getTimestamp("create_time").toLocalDateTime(), List.of());

    private static final RowMapper<InboundOrder.Item> ITEM = (rs, i) -> new InboundOrder.Item(
            rs.getLong("id"), rs.getString("barcode"), rs.getLong("sku_id"), rs.getInt("qty"),
            rs.getBigDecimal("purchase_price"), rs.getString("spu_no"), rs.getString("spu_name"),
            rs.getString("size"), rs.getString("color"));

    private static final String HEADER_SQL = """
            SELECT o.id, o.order_no, o.warehouse_id, o.supplier_id, sup.supplier_name,
                   o.operator_id, o.operator_name, o.status, o.total_qty, o.total_cost, o.remark,
                   o.submit_time, o.create_time
            FROM biz_inbound_order o LEFT JOIN cat_supplier sup ON sup.id = o.supplier_id
            """;

    private static final String ITEM_SQL = """
            SELECT i.id, i.barcode, i.sku_id, i.qty, i.purchase_price,
                   s.spu_no, s.spu_name, k.size, k.color
            FROM biz_inbound_order_item i
                     JOIN cat_sku k ON k.id = i.sku_id
                     JOIN cat_spu s ON s.id = k.spu_id
            WHERE i.order_id = ?
            ORDER BY i.id ASC
            """;

    @Override
    public long insertHeader(String orderNo, long warehouseId, long supplierId, long operatorId,
                             String operatorName, String remark) {
        return insert("""
                        INSERT INTO biz_inbound_order (order_no, warehouse_id, supplier_id, operator_id,
                                                       operator_name, status, remark)
                        VALUES (?, ?, ?, ?, ?, 'DRAFT', ?)
                        """,
                orderNo, warehouseId, supplierId, operatorId, operatorName, remark);
    }

    @Override
    public Optional<InboundOrder> findByNo(String orderNo) {
        Optional<InboundOrder> header = queryOne(HEADER_SQL + " WHERE o.order_no = ?", HEADER, orderNo);
        return header.map(h -> new InboundOrder(h.id(), h.orderNo(), h.warehouseId(), h.supplierId(),
                h.supplierName(), h.operatorId(), h.operatorName(), h.status(), h.totalQty(), h.totalCost(),
                h.remark(), h.submitTime(), h.createTime(), queryList(ITEM_SQL, ITEM, h.id())));
    }

    @Override
    public Optional<InboundOrder> findById(long id) {
        Optional<InboundOrder> header = queryOne(HEADER_SQL + " WHERE o.id = ?", HEADER, id);
        return header.map(h -> new InboundOrder(h.id(), h.orderNo(), h.warehouseId(), h.supplierId(),
                h.supplierName(), h.operatorId(), h.operatorName(), h.status(), h.totalQty(), h.totalCost(),
                h.remark(), h.submitTime(), h.createTime(), queryList(ITEM_SQL, ITEM, h.id())));
    }

    @Override
    public List<InboundOrder> list(int limit, int offset) {
        List<InboundOrder> headers = queryList(HEADER_SQL + " ORDER BY o.create_time DESC LIMIT ? OFFSET ?",
                HEADER, limit, offset);
        return headers.stream()
                .map(h -> new InboundOrder(h.id(), h.orderNo(), h.warehouseId(), h.supplierId(), h.supplierName(),
                        h.operatorId(), h.operatorName(), h.status(), h.totalQty(), h.totalCost(), h.remark(),
                        h.submitTime(), h.createTime(), queryList(ITEM_SQL, ITEM, h.id())))
                .toList();
    }

    @Override
    public boolean appendItem(long orderId, String barcode, long skuId, int qty, BigDecimal purchasePrice) {
        try {
            insert("""
                            INSERT INTO biz_inbound_order_item (order_id, barcode, sku_id, qty, purchase_price)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    orderId, barcode, skuId, qty, purchasePrice);
            return true;
        } catch (DuplicateKeyException e) {
            // Q41「同一个码重复扫码不做处理」：不是错误，是设计行为
            return false;
        }
    }

    @Override
    public Optional<String> deleteLastItem(long orderId) {
        Optional<String> last = queryOne("""
                        SELECT barcode FROM biz_inbound_order_item
                        WHERE order_id = ? ORDER BY id DESC LIMIT 1
                        """,
                (rs, i) -> rs.getString(1), orderId);
        last.ifPresent(barcode -> update("""
                        DELETE FROM biz_inbound_order_item
                        WHERE order_id = ? AND barcode = ?
                        """, orderId, barcode));
        return last;
    }

    @Override
    public void refreshTotals(long orderId) {
        update("""
                        UPDATE biz_inbound_order o
                        SET o.total_qty  = (SELECT COALESCE(SUM(qty),0) FROM biz_inbound_order_item WHERE order_id = o.id),
                            o.total_cost = (SELECT COALESCE(SUM(qty * purchase_price),0) FROM biz_inbound_order_item WHERE order_id = o.id)
                        WHERE o.id = ?
                        """, orderId);
    }

    @Override
    public void markSubmitted(long orderId) {
        update("UPDATE biz_inbound_order SET status = 'SUBMITTED', submit_time = NOW(3) WHERE id = ?", orderId);
    }

    @Override
    public void cancel(String orderNo) {
        update("UPDATE biz_inbound_order SET status = 'CANCELLED' WHERE order_no = ? AND status = 'DRAFT'", orderNo);
    }
}
