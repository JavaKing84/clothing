package com.yilian.clothing.domain.service;

import com.yilian.clothing.domain.model.StockMatrix;
import com.yilian.clothing.domain.model.StockMatrixRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵透视（Q52 / ADR-07）。
 *
 * <p>断言三件事：尺码按业务顺序（不是字典序）、颜色按总库存降序、
 * 只放非零格。第三条是契约而不是优化——前端直接渲染 cells，
 * 出现 0 就会画出一格"有货但显示 0"的误导色块。</p>
 */
class StockMatrixBuilderTest {

    private static StockMatrixRow row(String spu, String size, String color, int qty) {
        return new StockMatrixRow((long) Math.abs((spu + size + color).hashCode()), spu, "针织衫",
                size, color, "上装", "2026秋", qty);
    }

    @Test
    @DisplayName("尺码按 XS→S→M→L→XL 排序，字典序会把 L 排到 M 前面")
    void sizeOrderIsBusinessOrder() {
        var rows = List.of(
                row("W26-1001", "XXL", "黑", 1), row("W26-1001", "L", "黑", 1),
                row("W26-1001", "S", "黑", 1), row("W26-1001", "XL", "黑", 1),
                row("W26-1001", "M", "黑", 1), row("W26-1001", "XS", "黑", 1));

        var matrix = StockMatrixBuilder.build(rows);

        assertThat(matrix.rows()).extracting(StockMatrix.Row::size)
                .containsExactly("XS", "S", "M", "L", "XL", "XXL");
    }

    @Test
    @DisplayName("未知尺码排在最后，且组内按字母稳定")
    void unknownSizesGoLast() {
        var rows = List.of(row("W26-1001", "均码", "黑", 1), row("W26-1001", "M", "黑", 1),
                row("W26-1001", "F", "黑", 1), row("W26-1001", "S", "黑", 1));

        assertThat(StockMatrixBuilder.build(rows).rows()).extracting(StockMatrix.Row::size)
                .containsExactly("S", "M", "F", "均码");
    }

    @Test
    @DisplayName("颜色列按总库存降序，热销色排左边")
    void colorsSortedByTotalDesc() {
        var rows = List.of(row("W26-1001", "S", "黑色", 3), row("W26-1001", "M", "黑色", 2),
                row("W26-1001", "S", "米白", 7), row("W26-1001", "M", "杏色", 1));

        var matrix = StockMatrixBuilder.build(rows);

        assertThat(matrix.colors()).containsExactly("米白", "黑色", "杏色");
        assertThat(matrix.colorTotals()).containsEntry("米白", 7).containsEntry("黑色", 5).containsEntry("杏色", 1);
        assertThat(matrix.grandTotal()).isEqualTo(13);
    }

    @Test
    @DisplayName("零库存格不上矩阵，行合计等于各格之和")
    void zeroCellsAreDropped() {
        var rows = List.of(row("W26-1001", "S", "黑色", 4), row("W26-1001", "S", "米白", 0),
                row("W26-1001", "M", "黑色", 0));

        var matrix = StockMatrixBuilder.build(rows);

        var sRow = matrix.rows().stream().filter(r -> r.size().equals("S")).findFirst().orElseThrow();
        assertThat(sRow.cells()).containsOnlyKeys("黑色").containsEntry("黑色", 4);
        assertThat(sRow.rowTotal()).isEqualTo(4);
        assertThat(matrix.rows()).allSatisfy(r -> {
            assertThat(r.cells().values()).doesNotContain(0);
            assertThat(r.rowTotal()).isEqualTo(r.cells().values().stream().mapToInt(Integer::intValue).sum());
        });
    }

    @Test
    @DisplayName("同一 SKU 跨多仓有多行时，透视必须求和，否则补货会补错")
    void sameSkuAcrossWarehousesIsSummed() {
        var rows = List.of(row("W26-1001", "S", "黑色", 3), row("W26-1001", "S", "黑色", 5));

        var matrix = StockMatrixBuilder.build(rows);

        assertThat(matrix.rows()).hasSize(1);
        assertThat(matrix.rows().get(0).cells()).containsEntry("黑色", 8);
    }

    @Test
    @DisplayName("空输入返回空矩阵而不是 null")
    void emptyInput() {
        var matrix = StockMatrixBuilder.build(List.of());
        assertThat(matrix.colors()).isEmpty();
        assertThat(matrix.rows()).isEmpty();
        assertThat(matrix.grandTotal()).isZero();
    }
}
