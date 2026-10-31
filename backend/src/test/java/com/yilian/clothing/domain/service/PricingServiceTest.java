package com.yilian.clothing.domain.service;

import com.yilian.clothing.domain.model.SaleOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 金额口径的守卫（Q11/Q49/Q59）。
 *
 * <p>这些断言盯的是"钱算得对不对"，不是"代码跑不跑得通"。它们红了就说明
 * 报表不可信——而报表不可信比系统崩了更难发现：店主不会报错，
 * 只会觉得"这个软件算得不准"。</p>
 */
class PricingServiceTest {

    private static SaleOrder.Item item(long skuId, String tag, int qty) {
        return new SaleOrder.Item(null, skuId, 1L, "P" + skuId, "测试款", "M", "黑", qty,
                new BigDecimal(tag), new BigDecimal(tag), new BigDecimal("30.00"), null);
    }

    @Test
    @DisplayName("3 件各 10 元让 1 元：最后一件吃掉分摊误差，实收精确等于 29.00")
    void lastItemEatsRoundingDifference() {
        var items = List.of(item(1, "10.00", 1), item(2, "10.00", 1), item(3, "10.00", 1));
        var priced = PricingService.allocateDiscount(items, new BigDecimal("1.00"));

        assertThat(priced).extracting(SaleOrder.Item::actualPrice)
                .containsExactly(new BigDecimal("9.67"), new BigDecimal("9.67"), new BigDecimal("9.66"));
        assertThat(PricingService.actualTotal(priced))
                .isEqualByComparingTo(PricingService.tagTotal(items).subtract(new BigDecimal("1.00")));
    }

    @Test
    @DisplayName("多件行混合折扣：单价只有两位小数，Σ(单价×件数) 仍必须精确等于实收（真实缺陷回归）")
    void multiQtyLinesKeepCentExactness() {
        var items = List.of(
                item(1, "33.33", 1), item(2, "29.90", 2), item(3, "15.55", 1),
                item(4, "99.00", 1), item(5, "8.80", 3), item(6, "120.00", 1),
                item(7, "45.60", 1));
        var discount = new BigDecimal("9.99");
        int[] residual = new int[1];

        var priced = PricingService.allocateDiscount(items, discount, residual);

        assertThat(PricingService.actualTotal(priced))
                .as("整单实收 = 吊牌合计 - 折扣，一分不差")
                .isEqualByComparingTo(PricingService.tagTotal(items).subtract(discount));
        assertThat(residual[0]).as("存在 qty=1 的行时整数方程必然有解").isZero();
    }

    @Test
    @DisplayName("数学上无解时如实报告残差，而不是假装精确")
    void impossibleResidualIsReported() {
        // 3 行都是 2 件、折扣 1 分：单价调 1 分，行金额动 2 分，可达集合全是偶数分
        var items = List.of(item(1, "10.00", 2), item(2, "10.00", 2), item(3, "10.00", 2));
        int[] residual = new int[1];

        var priced = PricingService.allocateDiscount(items, new BigDecimal("0.01"), residual);

        assertThat(residual[0]).as("无解时必须暴露残差，供人工调账").isNotZero();
        assertThat(PricingService.actualTotal(priced))
                .isCloseTo(PricingService.tagTotal(items).subtract(new BigDecimal("0.01")),
                        org.assertj.core.data.Offset.offset(new BigDecimal("0.02")));
    }

    @Test
    @DisplayName("分摊是纯函数：不改入参，返回新列表")
    void allocationIsPure() {
        var items = List.of(item(1, "50.00", 1), item(2, "50.00", 1));
        var priced = PricingService.allocateDiscount(items, new BigDecimal("10.00"));

        assertThat(items).allSatisfy(i -> assertThat(i.actualPrice()).isEqualByComparingTo(i.tagPrice()));
        assertThat(priced).isNotSameAs(items);
    }

    @Test
    @DisplayName("无折扣 / 零折扣：折后价等于吊牌价")
    void noDiscount() {
        var items = List.of(item(1, "88.00", 2));
        assertThat(PricingService.allocateDiscount(items, null).get(0).actualPrice())
                .isEqualByComparingTo("88.00");
        assertThat(PricingService.allocateDiscount(items, BigDecimal.ZERO).get(0).actualPrice())
                .isEqualByComparingTo("88.00");
    }

    @Test
    @DisplayName("毛利按实收算，抹零的 5 元不算利润")
    void grossProfitUsesReceived() {
        var items = List.of(item(1, "100.00", 2));
        BigDecimal cost = PricingService.costTotal(items);

        assertThat(cost).isEqualByComparingTo("60.00");
        assertThat(PricingService.grossProfit(new BigDecimal("195.00"), cost)).isEqualByComparingTo("135.00");
        assertThat(PricingService.grossProfit(new BigDecimal("200.00"), cost)).isEqualByComparingTo("140.00");
    }

    @Test
    @DisplayName("空明细不抛异常")
    void emptyItems() {
        assertThat(PricingService.allocateDiscount(List.of(), new BigDecimal("5.00"))).isEmpty();
        assertThat(PricingService.tagTotal(List.of())).isEqualByComparingTo("0.00");
    }
}
