package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.InboundOrder;

import java.util.List;
import java.util.Optional;

/** 入库单仓储端口。 */
public interface InboundRepository {

    long insertHeader(String orderNo, long warehouseId, long supplierId, long operatorId,
                      String operatorName, String remark);

    Optional<InboundOrder> findByNo(String orderNo);

    Optional<InboundOrder> findById(long id);

    List<InboundOrder> list(int limit, int offset);

    /**
     * 追加明细。返回 false 表示撞了唯一索引 (order_id, barcode)——
     * 这就是 Q41「同一个码重复扫码不做处理」的全部实现。
     */
    boolean appendItem(long orderId, String barcode, long skuId, int qty, java.math.BigDecimal purchasePrice);

    /** 撤销上一件（Q43）：删掉最后插入的一条草稿明细，返回被删的条码。 */
    Optional<String> deleteLastItem(long orderId);

    void refreshTotals(long orderId);

    void markSubmitted(long orderId);

    void cancel(String orderNo);
}
