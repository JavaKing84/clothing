package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.Permission;
import com.yilian.clothing.domain.model.ReportViews;
import com.yilian.clothing.domain.repository.ReportRepository;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * 报表用例（Q59/Q63）。
 *
 * <p>所有报表都从流水出（ADR：账本比单据更接近事实）。毛利相关接口额外要求
 * {@code report:profit:read}，店员请求时**不是返回 0，而是直接 403**——
 * 返回 0 会让店员以为"今天没赚钱"，那是错误信息。</p>
 */
@Service
public class ReportAppService {

    private final ReportRepository reportRepository;

    public ReportAppService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    public ReportViews.Overview overview(long warehouseId, LocalDate date) {
        Operator op = operator();
        return reportRepository.overview(warehouseId, date == null ? LocalDate.now() : date,
                op.can(Permission.REPORT_PROFIT_READ));
    }

    public List<ReportViews.Rank> ranking(long warehouseId, LocalDate from, LocalDate to, String dim, int limit) {
        return reportRepository.ranking(warehouseId, orDefault(from), orDefault(to), dim, limit <= 0 ? 10 : limit);
    }

    public List<ReportViews.Rank> slowMoving(long warehouseId, int days, int limit) {
        return reportRepository.slowMoving(warehouseId, days <= 0 ? 30 : days, limit <= 0 ? 20 : limit);
    }

    public List<ReportViews.NamedStat> bySupplier(long warehouseId, LocalDate from, LocalDate to) {
        return reportRepository.bySupplier(warehouseId, orDefault(from), orDefault(to),
                operator().can(Permission.REPORT_PROFIT_READ));
    }

    public List<ReportViews.NamedStat> byClerk(long warehouseId, LocalDate from, LocalDate to) {
        Operator op = operator();
        // 店员只能看自己那一行，哪怕他手动传了别人的 operatorId 也没用
        Long only = op.isOwner() ? null : op.id();
        return reportRepository.byClerk(warehouseId, orDefault(from), orDefault(to), only,
                op.can(Permission.REPORT_PROFIT_READ));
    }

    public ReportViews.ProfitSummary profit(long warehouseId, LocalDate from, LocalDate to) {
        Operator op = operator();
        if (!op.can(Permission.REPORT_PROFIT_READ)) {
            throw BizException.of(ResultCode.FORBIDDEN, "仅店主可查看利润");
        }
        return reportRepository.profit(warehouseId, orDefault(from), orDefault(to));
    }

    public List<ReportViews.TrendPoint> trend(long warehouseId, LocalDate from, LocalDate to) {
        return reportRepository.trend(warehouseId, orDefault(from).minusDays(6), orDefault(to));
    }

    /** Q63：导出 Excel。用 POI 的 SXSSF 之外的最简方式——数据量上限 5000 行，XSSF 足够。 */
    public byte[] export(String type, long warehouseId, LocalDate from, LocalDate to) {
        Operator op = operator();
        boolean withCost = op.canSeeCost();
        String safeType = List.of("sales", "stock", "ledger").contains(type) ? type : "sales";
        List<List<Object>> rows = reportRepository.exportRows(safeType, warehouseId, from, to, withCost);

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName(safeType));
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            Row header = sheet.createRow(0);
            List<String> headers = headers(safeType, withCost);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }
            int r = 1;
            for (List<Object> data : rows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < data.size(); c++) {
                    Object v = data.get(c);
                    if (v == null) {
                        row.createCell(c).setBlank();
                    } else if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(String.valueOf(v));
                    }
                }
            }
            for (int i = 0; i < headers.size(); i++) {
                sheet.setColumnWidth(i, 16 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw BizException.of(ResultCode.SYSTEM_ERROR, "导出失败：" + e.getMessage());
        }
    }

    private List<String> headers(String type, boolean withCost) {
        return switch (type) {
            case "stock" -> List.of("货号", "品名", "尺码", "颜色", "品类", "季节", "库存", "安全库存");
            case "ledger" -> withCost
                    ? List.of("时间", "流水号", "类型", "货号", "品名", "尺码", "颜色", "数量", "金额",
                    "进价", "操作人", "状态", "备注")
                    : List.of("时间", "流水号", "类型", "货号", "品名", "尺码", "颜色", "数量", "金额",
                    "操作人", "状态", "备注");
            default -> withCost
                    ? List.of("日期", "单号", "店员", "货号", "品名", "尺码", "颜色", "数量", "实收", "成本", "毛利")
                    : List.of("日期", "单号", "店员", "货号", "品名", "尺码", "颜色", "数量", "实收");
        };
    }

    private String sheetName(String type) {
        return switch (type) {
            case "stock" -> "库存";
            case "ledger" -> "出入库流水";
            default -> "销售明细";
        };
    }

    private LocalDate orDefault(LocalDate d) {
        return d == null ? LocalDate.now() : d;
    }

    private Operator operator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }
}
