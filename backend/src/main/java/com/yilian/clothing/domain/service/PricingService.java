package com.yilian.clothing.domain.service;

import com.yilian.clothing.domain.model.SaleOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 定价与毛利（Q11/Q49）。
 *
 * <p>这个类只有静态纯函数，没有依赖、没有状态——因为它算的是钱，
 * 必须能脱离 Spring、脱离数据库被单独测试几千次。{@code PricingServiceTest}
 * 就是拿一堆边界值（0.01、抹零、整单折扣、单件折扣）把这里钉死。</p>
 */
public final class PricingService {

    private PricingService() {
    }

    /**
     * 把整单折扣按吊牌金额占比分摊到每件，得到"折后单价"。
     *
     * <p>为什么必须分摊而不是"记在单头"：销量排行要看毛利，毛利 = 实收 - 成本。
     * 如果折扣只挂在单头，单件毛利好算，但"哪一款在赚钱"就算不出来——
     * 而 Q59 明确要"销量排行 + 毛利"。</p>
     *
     * <p>分摊用"最后一件吃差额"的方式收口，保证 {@code Σ(actualPrice*qty) + discount == Σ(tagPrice*qty)}
     * 精确到分。四舍五入到每件再求和，会差几分钱，财务对不上账。</p>
     */
    public static List<SaleOrder.Item> allocateDiscount(List<SaleOrder.Item> items, BigDecimal discount) {
        return allocateDiscount(items, discount, null);
    }

    /**
     * 分摊折扣的完整版本，可回吐"无法用分表示"的残差（单位：分）。
     *
     * <p>为什么需要它：明细表只存 {@code actual_price}（单价，两位小数），
     * 行金额是 {@code 单价 × 件数} 现算的。所以"行金额精确"并不等于"能存进去"——
     * 26.41 元摊 3 件，单价 8.8033 存进去变 8.80，回算成 26.40，整单少收 1 分。
     * 这是分摊逻辑在"多件行"上的真实缺陷，单件行（qty=1）永远看不出问题，
     * 所以单测里必须专门造多件行。</p>
     *
     * <p>修法是把问题还原成整数方程：求整数单价 {@code u_i}（单位：分）使
     * {@code Σ u_i × q_i == 整单实收}。每行调 1 分，行金额变动 {@code q_i} 分，
     * 残差可达集合是 {@code {Σ k_i q_i}}，只要 {@code gcd(q_i) | r} 就有解；
     * 现实订单几乎总有 qty=1 的行（gcd=1），因此恒有解。
     * 仅当"所有行都在 2 件以上且 gcd 不整除残差"时无解，
     * 此时把残差并入最大件数行并通过 {@code residualCents} 如实暴露。</p>
     *
     * @param residualCents 长度为 1 的数组则写入残差（分），可传 {@code null}
     */
    public static List<SaleOrder.Item> allocateDiscount(List<SaleOrder.Item> items, BigDecimal discount,
                                                       int[] residualCents) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (discount == null || discount.signum() <= 0) {
            List<SaleOrder.Item> same = items.stream()
                    .map(i -> new SaleOrder.Item(i.id(), i.skuId(), i.spuId(), i.spuNo(), i.spuName(), i.size(),
                            i.color(), i.qty(), i.tagPrice(), i.tagPrice(), i.costPrice(), i.flowNo()))
                    .toList();
            if (residualCents != null) {
                residualCents[0] = 0;
            }
            return same;
        }
        BigDecimal tagTotal = tagTotal(items);
        if (tagTotal.signum() == 0) {
            return items;
        }

        int n = items.size();
        BigDecimal target = tagTotal.subtract(discount);
        BigDecimal[] units = new BigDecimal[n];
        int[] qty = new int[n];

        // 第一层：行级精确 —— 前 n-1 行按占比摊，最后一行吃掉行级舍入误差
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            SaleOrder.Item item = items.get(i);
            BigDecimal lineDiscount;
            if (i == n - 1) {
                lineDiscount = discount.subtract(allocated);
            } else {
                lineDiscount = discount.multiply(item.lineTagAmount())
                        .divide(tagTotal, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(lineDiscount);
            }
            BigDecimal lineActual = item.lineTagAmount().subtract(lineDiscount);
            qty[i] = item.qty();
            units[i] = lineActual.divide(BigDecimal.valueOf(Math.max(item.qty(), 1)), 2, RoundingMode.HALF_UP);
        }

        // 第二层：单价只能到分 —— 在"分"的整数域里解 Σ u_i·q_i = 实收
        int[] changes = new int[n];
        int rest = solveResidual(target, units, qty, changes);

        List<SaleOrder.Item> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SaleOrder.Item item = items.get(i);
            BigDecimal unit = units[i].add(BigDecimal.valueOf(changes[i]).movePointLeft(2));
            if (unit.signum() < 0) {
                // 单价为负没有业务含义，把这 1 分退回残差里报出来
                rest += changes[i] * Math.max(item.qty(), 1);
                unit = units[i];
            }
            result.add(new SaleOrder.Item(item.id(), item.skuId(), item.spuId(), item.spuNo(), item.spuName(),
                    item.size(), item.color(), item.qty(), item.tagPrice(), unit, item.costPrice(), item.flowNo()));
        }
        if (residualCents != null) {
            residualCents[0] = rest;
        }
        return result;
    }

    /** 元 → 分的整数。金额一旦进入"找零残差"运算就必须换整数域，反复四舍五入自己就会造出 1 分误差。 */
    private static int cents(BigDecimal value) {
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** 求单价调整量（分），使 {@code Σ (u_i + changes_i) × q_i == target}。返回剩余无法表示的残差（分）。 */
    private static int solveResidual(BigDecimal target, BigDecimal[] units, int[] qty, int[] changes) {
        BigDecimal current = BigDecimal.ZERO;
        for (int i = 0; i < units.length; i++) {
            current = current.add(units[i].multiply(BigDecimal.valueOf(qty[i])));
        }
        int rest = cents(target) - cents(current);
        if (rest == 0) {
            return 0;
        }
        Integer[] order = new Integer[qty.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // 件数少的行优先：qty=1 调 1 分正好抵 1 分残差，最不容易把单价调歪
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> qty[i]));

        while (rest != 0) {
            boolean moved = false;
            for (int i : order) {
                int q = qty[i];
                if (q <= 0 || Math.abs(rest) < q) {
                    continue;
                }
                int k = rest / q;
                if (k == 0) {
                    continue;
                }
                changes[i] += k;
                rest -= k * q;
                moved = true;
            }
            if (moved) {
                continue;
            }
            int[] pair = solvePair(rest, qty, order);
            if (pair == null) {
                break;
            }
            changes[pair[0]] += pair[2];
            changes[pair[1]] -= pair[3];
            rest = 0;
        }
        return rest;
    }

    /** 小范围枚举两行组合 {@code k·q_i − m·q_j = rest}，覆盖"3−2=1"这类互质搭配。 */
    private static int[] solvePair(int rest, int[] qty, Integer[] order) {
        for (int i : order) {
            for (int j : order) {
                if (i == j || qty[i] == 0 || qty[j] == 0) {
                    continue;
                }
                for (int k = 1; k <= 4; k++) {
                    for (int m = 0; m <= 4; m++) {
                        if (k * qty[i] - m * qty[j] == rest) {
                            return new int[]{i, j, k, m};
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 成本合计：按明细的进货价快照求和（历史成本不可变） */
    public static BigDecimal costTotal(List<SaleOrder.Item> items) {
        return items.stream()
                .map(SaleOrder.Item::lineCostAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal tagTotal(List<SaleOrder.Item> items) {
        return items.stream()
                .map(SaleOrder.Item::lineTagAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal actualTotal(List<SaleOrder.Item> items) {
        return items.stream()
                .map(SaleOrder.Item::lineActualAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 毛利 = 实收 - 成本。
     * 注意参数是"实收"而不是"吊牌合计"——Q49 允许手工输入实收，抹零的那几块钱不能算成利润。
     */
    public static BigDecimal grossProfit(BigDecimal received, BigDecimal cost) {
        return received.subtract(cost).setScale(2, RoundingMode.HALF_UP);
    }
}
