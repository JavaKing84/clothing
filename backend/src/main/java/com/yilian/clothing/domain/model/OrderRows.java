package com.yilian.clothing.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 辅助单据读模型：退货单与调拨单。
 *
 * <p>它们没有复杂的领域行为（退货就是"加回库存+退钱"，调拨就是"一出一入"），
 * 因此不建独立聚合根，只在读侧保留结构，写侧由应用服务串起领域服务。</p>
 */
public final class OrderRows {

    private OrderRows() {
    }

    public record ReturnRow(
            Long id,
            String orderNo,
            String saleOrderNo,
            Long warehouseId,
            String returnType,
            int qty,
            BigDecimal refundAmount,
            boolean restock,
            String reason,
            String operatorName,
            LocalDateTime createTime
    ) {
    }

    public record TransferRow(
            Long id,
            String orderNo,
            Long fromWarehouseId,
            Long toWarehouseId,
            int qty,
            String status,
            String operatorName,
            String remark,
            LocalDateTime createTime
    ) {
    }
}
