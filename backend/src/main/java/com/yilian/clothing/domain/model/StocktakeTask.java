package com.yilian.clothing.domain.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 盘点任务聚合（Q57/Q58）。
 *
 * <p>为什么盘点要"快照"而不是"边盘边改"：
 * 门店不能停业，盘点期间仍在卖货。若直接用当前库存做比对，
 * 「盘点开始后卖掉的那件」会被算成盘亏。因此建单时快照 {@code bookQty}，
 * 结束时空差异时再按期间销量拆解（ADR-13）。</p>
 */
public record StocktakeTask(
        Long id,
        String taskNo,
        Long warehouseId,
        String status,
        int totalItems,
        int scannedItems,
        int diffItems,
        Long operatorId,
        String operatorName,
        LocalDateTime finishTime,
        LocalDateTime createTime,
        List<Item> items
) {

    public static final String DOING = "DOING";
    public static final String DONE = "DONE";

    public boolean doing() {
        return DOING.equals(status);
    }

    public record Item(
            Long id,
            Long skuId,
            String spuNo,
            String spuName,
            String size,
            String color,
            int bookQty,
            int actualQty,
            int bookVersion
    ) {
        /** 差异 > 0 盘盈，< 0 盘亏。0 表示无差异，不进差异清单。 */
        public int diff() {
            return actualQty - bookQty;
        }

        public boolean hasDiff() {
            return diff() != 0;
        }
    }
}
