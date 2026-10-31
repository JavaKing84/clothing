package com.yilian.clothing.domain.model;

/** 待补货清单行（Q55/Q56）。 */
public record StockAlert(
        Long skuId,
        String spuNo,
        String spuName,
        String size,
        String color,
        int quantity,
        int safetyStock
) {
}
