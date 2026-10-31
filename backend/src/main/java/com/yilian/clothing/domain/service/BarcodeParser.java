package com.yilian.clothing.domain.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 吊牌码解析器（Q26：码内含"货号+尺码+颜色+日期+供货商+一串条形码"）。
 *
 * <p>《答卷》只说了"里面包含什么"，没给**具体码制**。这是真实需求的常态：
 * 店主并不知道自己吊牌用的是哪种码，各供货商还不一样。因此这里做的是
 * <b>宽松解析 + 失败降级</b>：能解析就带出字段，解析不了就交给条码映射表
 * （cat_barcode）；两者都没有，就走 Q38「手动输入货号」。</p>
 *
 * <p>支持的三种现实形态：
 * <ol>
 *   <li>纯 EAN-13 / Code128：{@code 6901234567890} → 只当条码，字段全靠映射表</li>
 *   <li>竖线分隔（本项目自印码规范）：{@code W26-1001|M|碎花蓝|20260912|四季青A档|000123}</li>
 *   <li>井号分隔：{@code W26-1001#M#碎花蓝#...}（部分供货商的旧码）</li>
 * </ol></p>
 */
public final class BarcodeParser {

    private static final Pattern EAN13 = Pattern.compile("^\\d{13}$");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private BarcodeParser() {
    }

    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Parsed.empty(raw);
        }
        String code = raw.trim();
        if (EAN13.matcher(code).matches()) {
            return new Parsed(code, null, null, null, null, null, null, true);
        }
        String sep = code.contains("|") ? "\\|" : (code.contains("#") ? "#" : null);
        if (sep == null) {
            return Parsed.empty(code);
        }
        String[] parts = code.split(sep, -1);
        if (parts.length < 3) {
            return Parsed.empty(code);
        }
        String spuNo = blankToNull(parts[0]);
        String size = blankToNull(parts[1]);
        String color = blankToNull(parts[2]);
        LocalDate date = null;
        if (parts.length > 3 && parts[3] != null && parts[3].length() == 8) {
            try {
                date = LocalDate.parse(parts[3], YMD);
            } catch (Exception ignored) {
                // 日期格式不合规范就丢弃，不要让一个可选字段把整次扫码搞失败
            }
        }
        String supplier = parts.length > 4 ? blankToNull(parts[4]) : null;
        String serial = parts.length > 5 ? blankToNull(parts[5]) : null;
        return new Parsed(code, spuNo, size, color, date, supplier, serial, false);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * @param raw         原始扫码内容
     * @param spuNo       解析出的货号（可能为空）
     * @param plainBarcode 是否"只是条码"，没有任何内嵌字段
     */
    public record Parsed(
            String raw,
            String spuNo,
            String size,
            String color,
            LocalDate purchaseDate,
            String supplierName,
            String serial,
            boolean plainBarcode
    ) {
        static Parsed empty(String raw) {
            return new Parsed(raw, null, null, null, null, null, null, true);
        }

        public boolean hasStructuredFields() {
            return spuNo != null || size != null || color != null;
        }
    }
}
