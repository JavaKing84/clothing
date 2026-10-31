package com.yilian.clothing.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 报表读模型。集中在一个类里，是因为它们都是"查询投影"而不是聚合根，
 * 没有行为、没有生命周期，散成 7 个文件只会让包爆炸。
 */
public final class ReportViews {

    private ReportViews() {
    }

    /**
     * 首页总览（Q12：每天关门看营业额和利润）。
     *
     * @param profit 店员调用时为 null——不是 0，是"不存在"。0 会被前端画成"今天没赚钱"，
     *               那是错误信息；null 让前端直接不渲染这块卡片。
     */
    public record Overview(
            LocalDate date,
            BigDecimal todayAmount,
            int todayQty,
            int todayOrders,
            BigDecimal weekAmount,
            BigDecimal monthAmount,
            BigDecimal todayProfit,
            long stockTotal,
            int alertCount,
            int pendingLoss
    ) {
    }

    public record Rank(String label, String subLabel, int qty, BigDecimal amount) {
    }

    public record NamedStat(String name, int qty, BigDecimal amount, BigDecimal profit) {
    }

    public record TrendPoint(LocalDate date, int qty, BigDecimal amount) {
    }

    /**
     * 毛利汇总。
     *
     * <p>{@code margin} 是毛利率（百分比，保留两位）。为什么由后端算而不是前端算：
     * 毛利率的分母是"实收"，实收口径一旦调整（比如以后要扣平台佣金），
     * 只改一处就能保证所有终端一致；交给前端算，十个页面就有十种口径。</p>
     */
    public record ProfitSummary(BigDecimal revenue, BigDecimal cost, BigDecimal gross, int qty,
                                BigDecimal margin) {
    }

    public record SlowMoving(String spuNo, String spuName, int stockQty, int soldQty, int daysSinceLastSale) {
    }
}
