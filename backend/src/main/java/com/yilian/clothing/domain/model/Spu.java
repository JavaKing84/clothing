package com.yilian.clothing.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 款式（SPU）。
 *
 * <p>注意 {@code purchasePrice} 允许为 null：这不是"数据缺失"，而是**字段级权限的产物**。
 * 店员查询时 SQL 根本不 SELECT 这一列（ADR-06 第 3 层），映射出来就是 null。
 * 序列化时用 {@code @JsonInclude(NON_NULL)} 让这个键彻底不出现在 JSON 里，
 * 而不是返回 {@code "purchasePrice": null}——后者等于告诉店员"这里有个你看不到的字段"。</p>
 */
public record Spu(
        Long id,
        String spuNo,
        String spuName,
        String category,
        String season,
        Long supplierId,
        String supplierName,
        BigDecimal purchasePrice,
        BigDecimal retailPrice,
        BigDecimal specialPrice,
        LocalDate purchaseDate,
        int status,
        int version,
        LocalDateTime createTime
) {
    public boolean onSale() {
        return status == 1;
    }

    /** 实际售价：有特价用特价，否则用吊牌价（Q35 特价商品） */
    public BigDecimal effectivePrice() {
        return specialPrice != null ? specialPrice : retailPrice;
    }
}
