package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.SaleOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 销售单仓储端口。 */
public interface SaleRepository {

    long insertOrder(String orderNo, long warehouseId, long operatorId, String operatorName, String remark);

    Optional<SaleOrder> findByNo(String orderNo, boolean withCost);

    Optional<SaleOrder> findById(long id, boolean withCost);

    List<SaleOrder> list(LocalDateTime from, LocalDateTime to, Long operatorId, int limit, int offset);

    /** 加购：已存在同 SKU 时 +qty，否则插入。返回明细行 ID。 */
    long upsertItem(long orderId, long skuId, long spuId, String spuNo, String spuName, String size, String color,
                    int qty, BigDecimal tagPrice, BigDecimal costPrice, String flowNo);

    void updateItemQty(long itemId, int qty, String flowNo);

    /** 结账时把分摊后的实收单价写回明细（Q49/Q50 的毛利口径依赖它） */
    void updateItemActualPrice(long itemId, java.math.BigDecimal actualPrice);

    Optional<Long> findOrderIdByItem(long itemId);

    Optional<Integer> itemQty(long itemId);

    void deleteItem(long itemId);

    void refreshTotals(long orderId);

    void checkout(long orderId, BigDecimal discountAmount, BigDecimal receivedAmount, String payType,
                  String customerName, String customerPhone, String remark);

    void cancel(long orderId);

    /** 超时未结账的 OPEN 单（ADR-11），返回单号列表 */
    List<String> findExpiredOpenOrders(LocalDateTime before);

    Optional<BigDecimal> itemLineAmount(long orderId, long skuId);
}
