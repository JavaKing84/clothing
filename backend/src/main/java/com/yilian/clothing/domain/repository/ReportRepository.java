package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.ReportViews;

import java.time.LocalDate;
import java.util.List;

/** 报表读仓储端口（只读，CQRS 的 Q 侧）。 */
public interface ReportRepository {

    ReportViews.Overview overview(long warehouseId, LocalDate date, boolean withProfit);

    List<ReportViews.Rank> ranking(long warehouseId, LocalDate from, LocalDate to, String dim, int limit);

    List<ReportViews.Rank> slowMoving(long warehouseId, int days, int limit);

    List<ReportViews.NamedStat> bySupplier(long warehouseId, LocalDate from, LocalDate to, boolean withProfit);

    List<ReportViews.NamedStat> byClerk(long warehouseId, LocalDate from, LocalDate to,
                                        Long onlyOperatorId, boolean withProfit);

    ReportViews.ProfitSummary profit(long warehouseId, LocalDate from, LocalDate to);

    List<ReportViews.TrendPoint> trend(long warehouseId, LocalDate from, LocalDate to);

    /** 导出用：明细行（sales 按单/件，stock 按 SKU，ledger 按流水） */
    List<List<Object>> exportRows(String type, long warehouseId, LocalDate from, LocalDate to,
                                  boolean withCost);
}
