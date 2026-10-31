package com.yilian.clothing.domain.service;

import com.yilian.clothing.domain.model.StockMatrix;
import com.yilian.clothing.domain.model.StockMatrixRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 长表 → 矩阵的透视器（ADR-07）。
 *
 * <p>为什么不在 SQL 里 `GROUP BY ... MAX(CASE WHEN color='黑' ...)`：
 * 颜色是开放集合，每加一个颜色就要改 SQL；而女装的"雾霾蓝""燕麦色"是季节性的，
 * 一年能冒出十几个。应用层透视只查一次库，同一份长表还能喂给导出与预警。</p>
 *
 * <p>复杂度 O(n)，n = 款×码×色，本项目 n ≈ 200，耗时 &lt; 1ms。瓶颈从来不在这一步。</p>
 */
public final class StockMatrixBuilder {

    private StockMatrixBuilder() {
    }

    public static StockMatrix build(List<StockMatrixRow> rows) {
        // 行序**不能**依赖 SQL 的 ORDER BY：仓储一改 SQL（比如多 join 一张表）顺序就漂，
        // 而前端表头与表体是按行序对齐渲染的，漂一次就是"尺码对错行"的严重误读。
        // 领域层自己兜住，代价是一次 O(n log n)，n≈200。
        List<StockMatrixRow> ordered = rows.stream()
                .sorted(java.util.Comparator.comparing(StockMatrixRow::spuNo)
                        .thenComparing(StockMatrixRow::size, sizeOrder()))
                .toList();

        // 颜色顺序：按总库存降序，同量按名称。热销色排左边，符合店主看表的习惯
        Map<String, Integer> colorQty = new LinkedHashMap<>();
        for (StockMatrixRow r : ordered) {
            colorQty.merge(r.color(), r.quantity(), Integer::sum);
        }
        List<String> colors = colorQty.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();

        // 行键 = 货号 + 尺码
        Map<String, Accum> rowMap = new LinkedHashMap<>();
        for (StockMatrixRow r : ordered) {
            String key = r.spuNo() + "|" + r.size();
            Accum acc = rowMap.computeIfAbsent(key,
                    k -> new Accum(r.spuNo(), r.spuName(), r.size(), r.category(), r.season()));
            if (r.quantity() != 0) {
                acc.cells.merge(r.color(), r.quantity(), Integer::sum);
                acc.total += r.quantity();
            }
        }

        List<StockMatrix.Row> out = new ArrayList<>(rowMap.size());
        for (Accum a : rowMap.values()) {
            out.add(new StockMatrix.Row(a.spuNo, a.spuName, a.size, a.category, a.season, a.total, a.cells));
        }
        int grand = out.stream().mapToInt(StockMatrix.Row::rowTotal).sum();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String c : colors) {
            totals.put(c, colorQty.getOrDefault(c, 0));
        }
        return new StockMatrix(colors, out, totals, grand);
    }

    /** 尺码排序权重：S < M < L < XL < XXL < 其余（按字母），否则 L 会排在 M 前面 */
    public static Comparator<String> sizeOrder() {
        List<String> std = List.of("XS", "S", "M", "L", "XL", "XXL", "XXXL");
        return Comparator.comparingInt((String s) -> {
            int idx = std.indexOf(s.toUpperCase());
            return idx < 0 ? 99 : idx;
        }).thenComparing(Comparator.naturalOrder());
    }

    private static final class Accum {
        final String spuNo;
        final String spuName;
        final String size;
        final String category;
        final String season;
        final Map<String, Integer> cells = new LinkedHashMap<>();
        int total;

        Accum(String spuNo, String spuName, String size, String category, String season) {
            this.spuNo = spuNo;
            this.spuName = spuName;
            this.size = size;
            this.category = category;
            this.season = season;
        }
    }

    /** 供测试用：去重后的颜色集合（保持输入顺序） */
    public static Set<String> colorsOf(List<StockMatrixRow> rows) {
        Set<String> set = new LinkedHashSet<>();
        rows.forEach(r -> set.add(r.color()));
        return set;
    }
}
