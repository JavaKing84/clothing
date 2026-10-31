package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.ReportAppService;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.ReportViews;
import com.yilian.clothing.infrastructure.security.RequirePermission;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 报表与导出（Q12/Q59/Q63）。 */
@Tag(name = "10 报表", description = "营业额/排行/滞销/毛利/导出")
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportAppService reportAppService;

    public ReportController(ReportAppService reportAppService) {
        this.reportAppService = reportAppService;
    }

    @Operation(summary = "首页总览（今日/本周/本月；店员看不到利润）")
    @GetMapping("/overview")
    public Result<ReportViews.Overview> overview(
            @RequestParam(defaultValue = "1") long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(reportAppService.overview(warehouseId, date));
    }

    @Operation(summary = "销量/金额排行（Q59）")
    @GetMapping("/ranking")
    public Result<List<ReportViews.Rank>> ranking(
            @RequestParam(defaultValue = "1") long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "qty") String dim,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(reportAppService.ranking(warehouseId, from, to, dim, limit));
    }

    @Operation(summary = "滞销排行（近 N 天零动销）")
    @GetMapping("/slow-moving")
    public Result<List<ReportViews.Rank>> slowMoving(@RequestParam(defaultValue = "1") long warehouseId,
                                                     @RequestParam(defaultValue = "30") int days,
                                                     @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(reportAppService.slowMoving(warehouseId, days, limit));
    }

    @Operation(summary = "按供货商统计")
    @GetMapping("/by-supplier")
    public Result<List<ReportViews.NamedStat>> bySupplier(
            @RequestParam(defaultValue = "1") long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(reportAppService.bySupplier(warehouseId, from, to));
    }

    @Operation(summary = "按店员统计（店员只看自己）")
    @GetMapping("/by-clerk")
    public Result<List<ReportViews.NamedStat>> byClerk(
            @RequestParam(defaultValue = "1") long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(reportAppService.byClerk(warehouseId, from, to));
    }

    @Operation(summary = "毛利报表（仅店主，Q68）")
    @GetMapping("/profit")
    @RequirePermission(Permission.REPORT_PROFIT_READ)
    public Result<ReportViews.ProfitSummary> profit(
            @RequestParam(defaultValue = "1") long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(reportAppService.profit(warehouseId, from, to));
    }

    @Operation(summary = "近 7 天趋势")
    @GetMapping("/trend")
    public Result<List<ReportViews.TrendPoint>> trend(
            @RequestParam(defaultValue = "1") long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(reportAppService.trend(warehouseId, from, to));
    }

    @Operation(summary = "导出 Excel（Q63）：type=sales|stock|ledger")
    @GetMapping("/export")
    @RequirePermission(Permission.EXPORT_RUN)
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "sales") String type,
            @RequestParam(defaultValue = "1") long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] bytes = reportAppService.export(type, warehouseId, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + type + "-report.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
