package com.yilian.clothing.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售单聚合（Q15/Q45/Q50）。
 *
 * <p>状态机：{@code OPEN}（选购中，库存已扣）→ {@code PAID}（已结账）或 {@code CANCELLED}（放弃，库存回补）。
 * 关键决策见 ADR-11：**加购即扣库存**，因为顾客把衣服拿在手上的那一刻，库存就该消失。</p>
 *
 * <p>金额三者关系（不变量）：{@code tagAmount - discountAmount ≈ receivedAmount}。
 * 用 "≈" 是因为 Q49 允许手输实收，允许抹零、允许"少收 5 块下次补"，
 * 因此不强制相等，但差异超过阈值要写 remark 留痕。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaleOrder(
        Long id,
        String orderNo,
        Long warehouseId,
        Long operatorId,
        String operatorName,
        String status,
        int totalQty,
        BigDecimal tagAmount,
        BigDecimal discountAmount,
        BigDecimal receivedAmount,
        BigDecimal costAmount,
        String payType,
        String customerName,
        String customerPhone,
        String remark,
        LocalDateTime checkoutTime,
        LocalDateTime createTime,
        List<Item> items
) {

    public static final String OPEN = "OPEN";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";

    public boolean open() {
        return OPEN.equals(status);
    }

    public boolean paid() {
        return PAID.equals(status);
    }

    /** 毛利：实收 - 成本。成本按明细的进货价快照逐件求和（Q11） */
    public BigDecimal grossProfit() {
        return receivedAmount.subtract(costAmount);
    }

    public record Item(
            Long id,
            Long skuId,
            Long spuId,
            String spuNo,
            String spuName,
            String size,
            String color,
            int qty,
            BigDecimal tagPrice,
            BigDecimal actualPrice,
            BigDecimal costPrice,
            String flowNo
    ) {
        public BigDecimal lineTagAmount() {
            return tagPrice.multiply(BigDecimal.valueOf(qty));
        }

        public BigDecimal lineActualAmount() {
            return actualPrice.multiply(BigDecimal.valueOf(qty));
        }

        public BigDecimal lineCostAmount() {
            return costPrice.multiply(BigDecimal.valueOf(qty));
        }

        public Item withQty(int newQty) {
            return new Item(id, skuId, spuId, spuNo, spuName, size, color, newQty, tagPrice, actualPrice, costPrice,
                    flowNo);
        }
    }
}
