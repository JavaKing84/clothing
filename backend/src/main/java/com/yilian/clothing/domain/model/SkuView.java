package com.yilian.clothing.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * SKU 视图（款 + 色 + 码），携带冗余的款式字段，供扫码/矩阵一次性取回。
 *
 * <p>为什么冗余 spuNo/spuName：扫码是最高频操作（Q9），每次扫码都 JOIN 三张表
 * 在 2000 件库存下无所谓，但在"双十一秒杀式"的连续扫码下会把连接池打满。
 * 冗余是空间换时间，代价是改款名要同步更新——本项目款名极少改，值得。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkuView(
        Long id,
        Long spuId,
        String skuCode,
        String spuNo,
        String spuName,
        String size,
        String color,
        String category,
        String season,
        Long supplierId,
        BigDecimal retailPrice,
        BigDecimal specialPrice,
        BigDecimal purchasePrice,
        int status
) {
    public BigDecimal tagPrice() {
        return specialPrice != null ? specialPrice : retailPrice;
    }
}
