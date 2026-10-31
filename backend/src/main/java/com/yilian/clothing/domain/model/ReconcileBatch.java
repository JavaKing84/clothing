package com.yilian.clothing.domain.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 离线对账批次（Q23）。一次同步 = 一个批次，批内逐条裁决。
 *
 * <p>四种结果的含义必须先说清，否则实现一定会退化成"整批成功/整批失败"：
 * <ul>
 *   <li>{@code ACCEPTED}：正常受理，已落库</li>
 *   <li>{@code DUPLICATED}：令牌已处理过，返回原来的流水号（幂等，不报错）</li>
 *   <li>{@code CONFLICTED}：库存不足等业务冲突，**未落库**，需人工处理</li>
 *   <li>{@code REJECTED}：数据本身不合法（条码不认识、数量 &lt;= 0），**未落库**</li>
 * </ul></p>
 */
public record ReconcileBatch(
        Long id,
        String batchNo,
        String deviceId,
        int total,
        int accepted,
        int duplicated,
        int conflicted,
        int rejected,
        LocalDateTime createTime,
        List<Item> items
) {

    public enum Result {
        ACCEPTED, DUPLICATED, CONFLICTED, REJECTED
    }

    public record Item(
            Long id,
            String clientToken,
            long deviceSeq,
            String barcode,
            int scanType,
            int quantity,
            Result result,
            String flowNo,
            String reason
    ) {
    }
}
