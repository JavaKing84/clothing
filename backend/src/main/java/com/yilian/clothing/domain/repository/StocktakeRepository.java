package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.LossCase;
import com.yilian.clothing.domain.model.StocktakeTask;

import java.util.List;
import java.util.Optional;

/** 盘点 + 挂账仓储端口。 */
public interface StocktakeRepository {

    long insertTask(String taskNo, long warehouseId, long operatorId, String operatorName);

    /** 建单时快照：把该仓所有 SKU 的账面数与版本一次性灌进明细表。 */
    int snapshotItems(long taskId, long warehouseId);

    Optional<StocktakeTask> findByNo(String taskNo, boolean withItems);

    /** 扫码：actual_qty + qty（同件扫两次算两件，这是盘点不是防重）。 */
    int scan(String taskNo, long skuId, int qty);

    void updateTotalItems(long taskId, int total);

    void bumpScanned(long taskId, int diff);

    void updateActual(long taskId, long skuId, int actualQty);

    List<StocktakeTask.Item> findDiffItems(long taskId);

    void finish(long taskId, int diffItems);

    Optional<LossCase> findLossByNo(String caseNo);

    long insertLoss(String caseNo, long skuId, long warehouseId, int qty, String reason,
                    long operatorId, String operatorName);

    List<LossCase> listLoss(String status, int limit, int offset);

    void closeLoss(String caseNo);

    int countOpenLoss();
}
