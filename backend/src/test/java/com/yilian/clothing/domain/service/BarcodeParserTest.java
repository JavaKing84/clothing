package com.yilian.clothing.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 吊牌码解析（Q24/Q26/Q36/Q38）。
 *
 * <p>核心契约：解析失败必须**降级**而不是抛错。店员在柜台前扫码，
 * 一个字段写错就让整次扫码失败，人会立刻放弃这个软件。</p>
 */
class BarcodeParserTest {

    @Test
    @DisplayName("EAN-13 纯数字只当条码，字段靠映射表补齐")
    void ean13IsPlain() {
        var parsed = BarcodeParser.parse("6901234567892");
        assertThat(parsed.plainBarcode()).isTrue();
        assertThat(parsed.hasStructuredFields()).isFalse();
    }

    @Test
    @DisplayName("竖线分隔的完整码：货号/尺码/颜色/日期/供货商/序号全解析")
    void pipeFormatFull() {
        var parsed = BarcodeParser.parse("W26-1003|L|深蓝|20260815|杭州锦绫|SN20260815001");
        assertThat(parsed.plainBarcode()).isFalse();
        assertThat(parsed.spuNo()).isEqualTo("W26-1003");
        assertThat(parsed.size()).isEqualTo("L");
        assertThat(parsed.color()).isEqualTo("深蓝");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(parsed.supplierName()).isEqualTo("杭州锦绫");
        assertThat(parsed.serial()).isEqualTo("SN20260815001");
    }

    @Test
    @DisplayName("井号分隔的旧码同样能解，否则店主换供货商就扫不动")
    void hashFormatSupported() {
        var parsed = BarcodeParser.parse("W26-1001#M#米白");
        assertThat(parsed.plainBarcode()).isFalse();
        assertThat(parsed.spuNo()).isEqualTo("W26-1001");
        assertThat(parsed.size()).isEqualTo("M");
        assertThat(parsed.color()).isEqualTo("米白");
    }

    @Test
    @DisplayName("日期非法只丢这个字段，其余照常可用")
    void badDateIsDroppedSilently() {
        var parsed = BarcodeParser.parse("W26-1003|L|深蓝|20261332|杭州锦绫|SN1");
        assertThat(parsed.purchaseDate()).isNull();
        assertThat(parsed.spuNo()).isEqualTo("W26-1003");
        assertThat(parsed.color()).isEqualTo("深蓝");
    }

    @Test
    @DisplayName("无法识别时退回手动输入（Q38），不抛异常")
    void unknownFormatFallsBack() {
        for (String raw : new String[]{"ABC", "W26-1003", "YL-W26-1003-深蓝-L", "", "   ", null}) {
            assertThat(BarcodeParser.parse(raw).plainBarcode())
                    .as("输入 %s 应降级为 plain", raw)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("分隔段不足三个时不足以定位 SKU，退回 plain 而不是瞎猜")
    void tooFewSegments() {
        assertThat(BarcodeParser.parse("W26-1003|L").plainBarcode()).isTrue();
    }
}
