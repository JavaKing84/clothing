package com.yilian.clothing.domain.model;

/** 矩阵视图的一行原料：长表（款+码+色+数量），透视在应用层做（ADR-07）。 */
public record StockMatrixRow(
        Long skuId,
        String spuNo,
        String spuName,
        String size,
        String color,
        String category,
        String season,
        int quantity
) {
}
