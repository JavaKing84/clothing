package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.domain.model.OrderRows;
import com.yilian.clothing.domain.repository.OrderAuxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** 退货 / 调拨仓储实现。 */
@Repository
public class OrderAuxRepositoryImpl extends JdbcSupport implements OrderAuxRepository {

    public OrderAuxRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public long insertReturn(String orderNo, String saleOrderNo, long warehouseId, String returnType,
                             int qty, BigDecimal refundAmount, boolean restock, String reason,
                             long operatorId, String operatorName) {
        return insert("""
                        INSERT INTO biz_return_order (order_no, sale_order_no, warehouse_id, return_type, qty,
                                                      refund_amount, restock, reason, operator_id, operator_name)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                orderNo, saleOrderNo, warehouseId, returnType, qty, refundAmount, restock ? 1 : 0,
                reason, operatorId, operatorName);
    }

    @Override
    public void insertReturnItem(long returnId, long skuId, int qty, BigDecimal refundAmount, String flowNo) {
        update("""
                        INSERT INTO biz_return_order_item (return_id, sku_id, qty, refund_amount, flow_no)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                returnId, skuId, qty, refundAmount, flowNo);
    }

    @Override
    public List<OrderRows.ReturnRow> listReturns(int limit, int offset) {
        return queryList("""
                        SELECT id, order_no, sale_order_no, warehouse_id, return_type, qty, refund_amount,
                               restock, reason, operator_name, create_time
                        FROM biz_return_order ORDER BY create_time DESC LIMIT ? OFFSET ?
                        """,
                (rs, i) -> new OrderRows.ReturnRow(rs.getLong("id"), rs.getString("order_no"),
                        rs.getString("sale_order_no"), rs.getLong("warehouse_id"), rs.getString("return_type"),
                        rs.getInt("qty"), rs.getBigDecimal("refund_amount"), rs.getInt("restock") == 1,
                        rs.getString("reason"), rs.getString("operator_name"),
                        rs.getTimestamp("create_time").toLocalDateTime()),
                limit, offset);
    }

    @Override
    public long insertTransfer(String orderNo, long fromWarehouseId, long toWarehouseId, int qty,
                               long operatorId, String operatorName, String remark) {
        return insert("""
                        INSERT INTO biz_transfer_order (order_no, from_warehouse_id, to_warehouse_id, qty,
                                                        status, operator_id, operator_name, remark)
                        VALUES (?, ?, ?, ?, 'DONE', ?, ?, ?)
                        """,
                orderNo, fromWarehouseId, toWarehouseId, qty, operatorId, operatorName, remark);
    }

    @Override
    public void insertTransferItem(long transferId, long skuId, int qty, String outFlowNo, String inFlowNo) {
        update("""
                        INSERT INTO biz_transfer_order_item (transfer_id, sku_id, qty, out_flow_no, in_flow_no)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                transferId, skuId, qty, outFlowNo, inFlowNo);
    }

    @Override
    public List<OrderRows.TransferRow> listTransfers(int limit, int offset) {
        return queryList("""
                        SELECT id, order_no, from_warehouse_id, to_warehouse_id, qty, status, operator_name,
                               remark, create_time
                        FROM biz_transfer_order ORDER BY create_time DESC LIMIT ? OFFSET ?
                        """,
                (rs, i) -> new OrderRows.TransferRow(rs.getLong("id"), rs.getString("order_no"),
                        rs.getLong("from_warehouse_id"), rs.getLong("to_warehouse_id"), rs.getInt("qty"),
                        rs.getString("status"), rs.getString("operator_name"), rs.getString("remark"),
                        rs.getTimestamp("create_time").toLocalDateTime()),
                limit, offset);
    }
}
