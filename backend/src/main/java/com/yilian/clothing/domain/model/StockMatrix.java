package com.yilian.clothing.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 库存矩阵视图（Q52：款式+尺码为行，颜色为列）。
 *
 * <p>返回结构是"列定义 + 稀疏单元格"，不是二维数组。原因：
 * 颜色是开放集合，前端需要知道"这一列是什么颜色"才能画表头；
 * 用二维数组就得靠下标对齐，错一列全表串位。</p>
 *
 * @param colors      颜色列顺序（按出现频次降序，热销色在左边）
 * @param rows        行（款+尺码）
 * @param colorTotals 每列合计，店主扫一眼就知道"哪个颜色压在手里"
 * @param grandTotal  总件数
 */
public record StockMatrix(
        List<String> colors,
        List<Row> rows,
        Map<String, Integer> colorTotals,
        int grandTotal
) {

    /**
     * @param cells 只放非零单元格，减少 60% 的 JSON 体积（空货号占多数）
     */
    public record Row(
            String spuNo,
            String spuName,
            String size,
            String category,
            String season,
            int rowTotal,
            Map<String, Integer> cells
    ) {
    }
}
