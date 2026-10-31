package com.yilian.clothing.domain.model;

import java.time.LocalDateTime;

/**
 * 「实物找不到」挂账（Q48）。
 *
 * <p>这是零售现场的真实需求：账上 3 件，货架只有 2 件，另一件可能被顾客拿乱放了。
 * 直接盘亏会把"暂时找不到"记成"丢了"，等找到了又要冲回，来回折腾。
 * 挂账 = 把疑点单独放一个篮子，账面库存不动，但店主能在首页看到"有 1 件待查"。</p>
 */
public record LossCase(
        Long id,
        String caseNo,
        Long skuId,
        String spuNo,
        String spuName,
        String size,
        String color,
        Long warehouseId,
        int qty,
        String reason,
        String status,
        Long operatorId,
        String operatorName,
        LocalDateTime closeTime,
        LocalDateTime createTime
) {
    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    public boolean open() {
        return OPEN.equals(status);
    }
}
