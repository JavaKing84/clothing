package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.OrderRows;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退货 / 调拨仓储端口。
 *
 * <p>两者放在一起，是因为它们的写操作模式完全一致：
 * 一张单头 + N 条明细 + 每条明细对应一两条流水号。合并端口减少样板代码，
 * 同时不掩盖"退货影响金额、调拨不影响金额"这个关键差异（差异在应用服务里表达）。</p>
 */
public interface OrderAuxRepository {

    long insertReturn(String orderNo, String saleOrderNo, long warehouseId, String returnType,
                      int qty, BigDecimal refundAmount, boolean restock, String reason,
                      long operatorId, String operatorName);

    void insertReturnItem(long returnId, long skuId, int qty, BigDecimal refundAmount, String flowNo);

    List<OrderRows.ReturnRow> listReturns(int limit, int offset);

    long insertTransfer(String orderNo, long fromWarehouseId, long toWarehouseId, int qty,
                        long operatorId, String operatorName, String remark);

    void insertTransferItem(long transferId, long skuId, int qty, String outFlowNo, String inFlowNo);

    List<OrderRows.TransferRow> listTransfers(int limit, int offset);
}
