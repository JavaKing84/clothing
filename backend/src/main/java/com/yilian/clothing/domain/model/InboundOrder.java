package com.yilian.clothing.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 入库单聚合（Q18/Q39/Q40）。
 *
 * <p>状态机：DRAFT（扫码录入中）→ SUBMITTED（已入库）。**没有"部分入库"状态**：
 * 一件货要么在账上，要么不在。中间态是账实不符的温床（与 ADR-05 同一原则）。</p>
 *
 * <p>Q42「累计件数」= totalQty，是 item 的 qty 之和，但**存在单头冗余字段**：
 * 顶部计数每秒刷新，如果每次都 SUM 明细，扫码高峰会把 CPU 打在这个 SUM 上。</p>
 */
public record InboundOrder(
        Long id,
        String orderNo,
        Long warehouseId,
        Long supplierId,
        String supplierName,
        Long operatorId,
        String operatorName,
        String status,
        int totalQty,
        BigDecimal totalCost,
        String remark,
        LocalDateTime submitTime,
        LocalDateTime createTime,
        List<Item> items
) {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";

    public boolean editable() {
        return DRAFT.equals(status);
    }

    public InboundOrder withItems(List<Item> newItems) {
        int qty = newItems.stream().mapToInt(Item::qty).sum();
        BigDecimal cost = newItems.stream()
                .map(i -> i.purchasePrice().multiply(BigDecimal.valueOf(i.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InboundOrder(id, orderNo, warehouseId, supplierId, supplierName, operatorId, operatorName,
                status, qty, cost, remark, submitTime, createTime, List.copyOf(newItems));
    }

    public List<Item> mutableItems() {
        return new ArrayList<>(items == null ? List.of() : items);
    }

    /**
     * @param barcode       扫到的原始码（Q41 同单同码唯一）
     * @param purchasePrice 进货价快照：进价会变，已入库的历史不能跟着变
     */
    public record Item(Long id, String barcode, Long skuId, int qty, BigDecimal purchasePrice,
                       String spuNo, String spuName, String size, String color) {
    }
}
