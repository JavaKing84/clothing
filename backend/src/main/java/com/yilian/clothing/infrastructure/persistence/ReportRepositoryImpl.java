package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.domain.model.ReportViews;
import com.yilian.clothing.domain.repository.ReportRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 报表读仓储实现（CQRS 的 Q 侧）。
 *
 * <p>所有金额都从 {@code inv_stock_flow.sale_price} 出，不从 {@code biz_sale_order} 出。
 * 原因：退货、撤销都会产生流水，但不会改销售单的金额；只查销售单会漏掉退货，
 * 导致"营业额"虚高。**账本比单据更接近事实**。</p>
 *
 * <p>性能：本店一年流水量级 ≈ 3 万行，加 {@code idx_time_type} 后
 * 任意区间聚合都是毫秒级，因此**不做预聚合**（rpt_daily_sales 只用于首页趋势缓存）。
 * 这是有意识的取舍：预聚合会带来"聚合表与流水不一致"的新问题，
 * 在数据量没到瓶颈前，引入它是负收益。</p>
 */
@Repository
public class ReportRepositoryImpl extends JdbcSupport implements ReportRepository {

    public ReportRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public ReportViews.Overview overview(long warehouseId, LocalDate date, boolean withProfit) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime weekStart = date.minusDays(date.getDayOfWeek().getValue() - 1L).atStartOfDay();
        LocalDateTime monthStart = date.withDayOfMonth(1).atStartOfDay();
        LocalDateTime nextDay = date.plusDays(1).atStartOfDay();

        // 一条 SQL 出today/week/month 三个口径：三次往返在手机弱网下就是 3 倍延迟。
        // 参数顺序必须与 SQL 里的 ? 一一对应——注意 month_amount 用的是全表 SUM，
        // 没有占位符，这里就不能多塞一个 monthStart（多一个参数 MySQL 直接报
        // "Parameter index out of range"，而报错信息不会告诉你是哪个参数多的）。
        Object[] args = {dayStart, dayStart, dayStart, weekStart, dayStart, warehouseId, monthStart, nextDay};
        ReportViews.Overview base = queryOne("""
                        SELECT COALESCE(SUM(CASE WHEN create_time >= ? THEN sale_price ELSE 0 END), 0)          AS today_amount,
                               COALESCE(SUM(CASE WHEN create_time >= ? AND flow_type = 2 THEN quantity ELSE 0 END), 0) AS today_qty,
                               COUNT(DISTINCT CASE WHEN create_time >= ? AND flow_type = 2 THEN biz_no END)     AS today_orders,
                               COALESCE(SUM(CASE WHEN create_time >= ? THEN sale_price ELSE 0 END), 0)          AS week_amount,
                               COALESCE(SUM(sale_price), 0)                                                     AS month_amount,
                               COALESCE(SUM(CASE WHEN create_time >= ?
                                   THEN sale_price - COALESCE(purchase_price, 0) * quantity ELSE 0 END), 0)      AS today_profit
                        FROM inv_stock_flow
                        WHERE warehouse_id = ? AND flow_type IN (2, 7, 8) AND status = 1
                          AND create_time >= ? AND create_time < ?
                        """,
                (rs, i) -> new ReportViews.Overview(date,
                        rs.getBigDecimal("today_amount"), rs.getInt("today_qty"), rs.getInt("today_orders"),
                        rs.getBigDecimal("week_amount"), rs.getBigDecimal("month_amount"),
                        withProfit ? rs.getBigDecimal("today_profit") : null,
                        0, 0, 0),
                args).orElse(new ReportViews.Overview(date, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO,
                BigDecimal.ZERO, withProfit ? BigDecimal.ZERO : null, 0, 0, 0));

        long[] counters = jdbc.queryForObject("""
                        SELECT (SELECT COALESCE(SUM(quantity), 0) FROM inv_sku_stock
                                WHERE warehouse_id = ? AND del_flag = 0)                                  AS stock_total,
                               (SELECT COUNT(*) FROM inv_sku_stock
                                WHERE warehouse_id = ? AND quantity <= safety_stock AND del_flag = 0)     AS alert_cnt,
                               (SELECT COUNT(*) FROM inv_loss_case WHERE status = 'OPEN')                  AS pending_loss
                        """,
                (rs, i) -> new long[]{rs.getLong("stock_total"), rs.getLong("alert_cnt"), rs.getLong("pending_loss")},
                warehouseId, warehouseId);
        return new ReportViews.Overview(date, base.todayAmount(), base.todayQty(), base.todayOrders(),
                base.weekAmount(), base.monthAmount(), base.todayProfit(),
                counters[0], (int) counters[1], (int) counters[2]);
    }

    @Override
    public List<ReportViews.Rank> ranking(long warehouseId, LocalDate from, LocalDate to, String dim, int limit) {
        // dim 只允许两个值，且在 SQL 里以枚举方式拼接（不是字符串拼接用户输入），杜绝注入
        String orderBy = "amount".equalsIgnoreCase(dim) ? "amount DESC" : "qty DESC";
        // 踩过的坑：Java 文本块会**吃掉每行行尾空格**，所以 `ORDER BY \"\"\"` 拼出来是
        // `ORDER BYqty DESC`——MySQL 报 "near 'BYqty DESC'"，看着像语法错，其实是少了一个空格。
        // 拼 SQL 关键字时不要把空格留在块边界上，用显式 " " 更稳。
        String sql = """
                SELECT s.spu_no, s.spu_name,
                       SUM(f.quantity)                     AS qty,
                       COALESCE(SUM(f.sale_price), 0)      AS amount
                FROM inv_stock_flow f JOIN cat_spu s ON s.id = f.spu_id
                WHERE f.warehouse_id = ? AND f.flow_type = 2 AND f.status = 1
                  AND f.create_time >= ? AND f.create_time < ?
                GROUP BY s.id, s.spu_no, s.spu_name
                ORDER BY""" + " " + orderBy + " LIMIT ?";
        return queryList(sql,
                (rs, i) -> new ReportViews.Rank(rs.getString("spu_no"), rs.getString("spu_name"),
                        rs.getInt("qty"), rs.getBigDecimal("amount")),
                warehouseId, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), limit);
    }

    @Override
    public List<ReportViews.Rank> slowMoving(long warehouseId, int days, int limit) {
        // 滞销 = 有库存 + 近 N 天没卖动。注意是"没卖动"而不是"卖得少"：
        // 女装的长尾款一个月不动销很正常，店主需要的是"该处理了"的清单
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return queryList("""
                        SELECT s.spu_no, s.spu_name,
                               COALESCE(SUM(st.quantity), 0) AS stock_qty,
                               COALESCE(MAX(sold.qty), 0)    AS sold_qty
                        FROM cat_spu s
                                 JOIN cat_sku k ON k.spu_id = s.id AND k.del_flag = 0
                                 JOIN inv_sku_stock st ON st.sku_id = k.id AND st.warehouse_id = ? AND st.del_flag = 0
                                 LEFT JOIN (SELECT spu_id, SUM(quantity) AS qty
                                            FROM inv_stock_flow
                                            WHERE flow_type = 2 AND status = 1 AND create_time >= ?
                                            GROUP BY spu_id) sold ON sold.spu_id = s.id
                        WHERE s.del_flag = 0
                        GROUP BY s.id, s.spu_no, s.spu_name
                        HAVING stock_qty > 0 AND COALESCE(MAX(sold.qty), 0) = 0
                        ORDER BY stock_qty DESC
                        LIMIT ?
                        """,
                (rs, i) -> new ReportViews.Rank(rs.getString("spu_no"), rs.getString("spu_name"),
                        rs.getInt("sold_qty"), BigDecimal.valueOf(rs.getInt("stock_qty"))),
                warehouseId, since, limit);
    }

    @Override
    public List<ReportViews.NamedStat> bySupplier(long warehouseId, LocalDate from, LocalDate to,
                                                  boolean withProfit) {
        return queryList("""
                        SELECT sup.supplier_name AS name,
                               SUM(f.quantity) AS qty,
                               COALESCE(SUM(f.sale_price), 0) AS amount,
                               COALESCE(SUM(f.sale_price - COALESCE(f.purchase_price, 0) * f.quantity), 0) AS profit
                        FROM inv_stock_flow f
                                 JOIN cat_spu s ON s.id = f.spu_id
                                 JOIN cat_supplier sup ON sup.id = s.supplier_id
                        WHERE f.warehouse_id = ? AND f.flow_type IN (2, 7, 8) AND f.status = 1
                          AND f.create_time >= ? AND f.create_time < ?
                        GROUP BY sup.id, sup.supplier_name
                        ORDER BY amount DESC
                        """,
                (rs, i) -> new ReportViews.NamedStat(rs.getString("name"), rs.getInt("qty"),
                        rs.getBigDecimal("amount"), withProfit ? rs.getBigDecimal("profit") : null),
                warehouseId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    @Override
    public List<ReportViews.NamedStat> byClerk(long warehouseId, LocalDate from, LocalDate to,
                                               Long onlyOperatorId, boolean withProfit) {
        String extra = onlyOperatorId == null ? "" : " AND f.operator_id = ?";
        List<Object> args = new ArrayList<>(List.of(warehouseId, from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
        if (onlyOperatorId != null) {
            args.add(onlyOperatorId);
        }
        return queryList("""
                        SELECT f.operator_name AS name,
                               SUM(f.quantity) AS qty,
                               COALESCE(SUM(f.sale_price), 0) AS amount,
                               COALESCE(SUM(f.sale_price - COALESCE(f.purchase_price, 0) * f.quantity), 0) AS profit
                        FROM inv_stock_flow f
                        WHERE f.warehouse_id = ? AND f.flow_type IN (2, 7, 8) AND f.status = 1
                          AND f.create_time >= ? AND f.create_time < ?
                        """ + extra + """
                        GROUP BY f.operator_id, f.operator_name
                        ORDER BY amount DESC
                        """,
                (rs, i) -> new ReportViews.NamedStat(rs.getString("name"), rs.getInt("qty"),
                        rs.getBigDecimal("amount"), withProfit ? rs.getBigDecimal("profit") : null),
                args.toArray());
    }

    @Override
    public ReportViews.ProfitSummary profit(long warehouseId, LocalDate from, LocalDate to) {
        return queryOne("""
                        SELECT COALESCE(SUM(sale_price), 0) AS revenue,
                               COALESCE(SUM(COALESCE(purchase_price, 0) * quantity), 0) AS cost,
                               COALESCE(SUM(sale_price - COALESCE(purchase_price, 0) * quantity), 0) AS gross,
                               COALESCE(SUM(CASE WHEN flow_type = 2 THEN quantity ELSE -quantity END), 0) AS qty
                        FROM inv_stock_flow
                        WHERE warehouse_id = ? AND flow_type IN (2, 7, 8) AND status = 1
                          AND create_time >= ? AND create_time < ?
                        """,
                (rs, i) -> {
                    var revenue = rs.getBigDecimal("revenue");
                    var cost = rs.getBigDecimal("cost");
                    var gross = rs.getBigDecimal("gross");
                    return new ReportViews.ProfitSummary(revenue, cost, gross, rs.getInt("qty"),
                            margin(revenue, gross));
                },
                warehouseId, from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .orElse(new ReportViews.ProfitSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                        BigDecimal.ZERO));
    }

    /**
     * 毛利率（%）= 毛利 / 实收 × 100，保留两位。
     * 实收为 0 时返回 0 而不是抛异常——新店当天没开单是常态，不能让首页 500。
     */
    private static BigDecimal margin(BigDecimal revenue, BigDecimal gross) {
        if (revenue == null || revenue.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return gross.multiply(BigDecimal.valueOf(100))
                .divide(revenue, 2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public List<ReportViews.TrendPoint> trend(long warehouseId, LocalDate from, LocalDate to) {
        return queryList("""
                        SELECT DATE(create_time) AS d,
                               COALESCE(SUM(CASE WHEN flow_type = 2 THEN quantity ELSE -quantity END), 0) AS qty,
                               COALESCE(SUM(sale_price), 0) AS amount
                        FROM inv_stock_flow
                        WHERE warehouse_id = ? AND flow_type IN (2, 7, 8) AND status = 1
                          AND create_time >= ? AND create_time < ?
                        GROUP BY DATE(create_time)
                        ORDER BY d
                        """,
                (rs, i) -> new ReportViews.TrendPoint(rs.getDate("d").toLocalDate(),
                        rs.getInt("qty"), rs.getBigDecimal("amount")),
                warehouseId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    @Override
    public List<List<Object>> exportRows(String type, long warehouseId, LocalDate from, LocalDate to,
                                         boolean withCost) {
        LocalDateTime start = from == null ? LocalDate.now().minusDays(30).atStartOfDay() : from.atStartOfDay();
        LocalDateTime end = (to == null ? LocalDate.now() : to).plusDays(1).atStartOfDay();
        return switch (type == null ? "sales" : type) {
            case "stock" -> queryList("""
                            SELECT s.spu_no, s.spu_name, k.size, k.color, s.category, s.season,
                                   st.quantity, st.safety_stock
                            FROM inv_sku_stock st
                                     JOIN cat_sku k ON k.id = st.sku_id AND k.del_flag = 0
                                     JOIN cat_spu s ON s.id = k.spu_id AND s.del_flag = 0
                            WHERE st.warehouse_id = ? AND st.del_flag = 0
                            ORDER BY s.spu_no, FIELD(k.size,'XS','S','M','L','XL','XXL','XXXL')
                            """,
                    (rs, i) -> {
                        List<Object> row = new ArrayList<>();
                        row.add(rs.getString("spu_no"));
                        row.add(rs.getString("spu_name"));
                        row.add(rs.getString("size"));
                        row.add(rs.getString("color"));
                        row.add(rs.getString("category"));
                        row.add(rs.getString("season"));
                        row.add(rs.getInt("quantity"));
                        row.add(rs.getInt("safety_stock"));
                        return row;
                    }, warehouseId);
            case "ledger" -> queryList("""
                            SELECT f.create_time, f.flow_no, f.flow_type, f.spu_no, f.spu_name, f.size, f.color,
                                   f.signed_qty, f.sale_price, f.purchase_price, f.operator_name, f.status, f.remark
                            FROM inv_stock_flow f
                            WHERE f.warehouse_id = ? AND f.create_time >= ? AND f.create_time < ?
                            ORDER BY f.create_time DESC
                            LIMIT 5000
                            """,
                    (rs, i) -> {
                        List<Object> row = new ArrayList<>();
                        row.add(rs.getTimestamp("create_time").toLocalDateTime().toString());
                        row.add(rs.getString("flow_no"));
                        row.add(rs.getInt("flow_type"));
                        row.add(rs.getString("spu_no"));
                        row.add(rs.getString("spu_name"));
                        row.add(rs.getString("size"));
                        row.add(rs.getString("color"));
                        row.add(rs.getInt("signed_qty"));
                        row.add(rs.getBigDecimal("sale_price"));
                        if (withCost) {
                            row.add(rs.getBigDecimal("purchase_price"));
                        }
                        row.add(rs.getString("operator_name"));
                        row.add(rs.getInt("status") == 1 ? "正常" : "已撤销");
                        row.add(rs.getString("remark"));
                        return row;
                    }, warehouseId, start, end);
            default -> queryList("""
                            SELECT DATE(f.create_time) AS d, f.biz_no, f.operator_name, f.spu_no, f.spu_name,
                                   f.size, f.color, f.quantity, f.sale_price, f.purchase_price
                            FROM inv_stock_flow f
                            WHERE f.warehouse_id = ? AND f.flow_type IN (2, 7, 8) AND f.status = 1
                              AND f.create_time >= ? AND f.create_time < ?
                            ORDER BY f.create_time DESC
                            LIMIT 5000
                            """,
                    (rs, i) -> {
                        List<Object> row = new ArrayList<>();
                        row.add(rs.getDate("d").toLocalDate().toString());
                        row.add(rs.getString("biz_no"));
                        row.add(rs.getString("operator_name"));
                        row.add(rs.getString("spu_no"));
                        row.add(rs.getString("spu_name"));
                        row.add(rs.getString("size"));
                        row.add(rs.getString("color"));
                        row.add(rs.getInt("quantity"));
                        row.add(rs.getBigDecimal("sale_price"));
                        if (withCost) {
                            BigDecimal cost = rs.getBigDecimal("purchase_price");
                            row.add(cost);
                            BigDecimal amount = rs.getBigDecimal("sale_price");
                            row.add(cost == null || amount == null ? null
                                    : amount.subtract(cost.multiply(BigDecimal.valueOf(rs.getInt("quantity")))));
                        }
                        return row;
                    }, warehouseId, start, end);
        };
    }

    /** 保留：把"距上次销售天数"算出来的工具（滞销页展示用） */
    static int daysBetween(LocalDate from, LocalDate to) {
        return (int) ChronoUnit.DAYS.between(from, to);
    }
}
