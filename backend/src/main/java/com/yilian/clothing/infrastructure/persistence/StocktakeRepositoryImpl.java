package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.domain.model.LossCase;
import com.yilian.clothing.domain.model.StocktakeTask;
import com.yilian.clothing.domain.repository.StocktakeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 盘点 + 挂账仓储实现。 */
@Repository
public class StocktakeRepositoryImpl extends JdbcSupport implements StocktakeRepository {

    public StocktakeRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    private static final RowMapper<StocktakeTask> TASK = (rs, i) -> new StocktakeTask(
            rs.getLong("id"), rs.getString("task_no"), rs.getLong("warehouse_id"), rs.getString("status"),
            rs.getInt("total_items"), rs.getInt("scanned_items"), rs.getInt("diff_items"),
            rs.getLong("operator_id"), rs.getString("operator_name"),
            rs.getTimestamp("finish_time") == null ? null : rs.getTimestamp("finish_time").toLocalDateTime(),
            rs.getTimestamp("create_time").toLocalDateTime(), List.of());

    private static final RowMapper<StocktakeTask.Item> ITEM = (rs, i) -> new StocktakeTask.Item(
            rs.getLong("id"), rs.getLong("sku_id"), rs.getString("spu_no"), rs.getString("spu_name"),
            rs.getString("size"), rs.getString("color"), rs.getInt("book_qty"), rs.getInt("actual_qty"),
            rs.getInt("book_version"));

    private static final String ITEM_SQL = """
            SELECT i.id, i.sku_id, s.spu_no, s.spu_name, k.size, k.color,
                   i.book_qty, i.actual_qty, i.book_version
            FROM inv_stocktake_item i
                     JOIN cat_sku k ON k.id = i.sku_id
                     JOIN cat_spu s ON s.id = k.spu_id
            WHERE i.task_id = ?
            ORDER BY s.spu_no, FIELD(k.size,'XS','S','M','L','XL','XXL','XXXL')
            """;

    @Override
    public long insertTask(String taskNo, long warehouseId, long operatorId, String operatorName) {
        return insert("""
                        INSERT INTO inv_stocktake_task (task_no, warehouse_id, operator_id, operator_name, status)
                        VALUES (?, ?, ?, ?, 'DOING')
                        """,
                taskNo, warehouseId, operatorId, operatorName);
    }

    @Override
    public int snapshotItems(long taskId, long warehouseId) {
        // 一条 INSERT ... SELECT 完成快照：不在应用层循环 300 次查询，
        // 否则建盘点单要几秒钟，店员以为卡死了
        return update("""
                        INSERT INTO inv_stocktake_item (task_id, sku_id, book_qty, actual_qty, book_version)
                        SELECT ?, st.sku_id, st.quantity, 0, st.version
                        FROM inv_sku_stock st
                                 JOIN cat_sku k ON k.id = st.sku_id AND k.del_flag = 0
                        WHERE st.warehouse_id = ? AND st.del_flag = 0
                        """,
                taskId, warehouseId);
    }

    @Override
    public Optional<StocktakeTask> findByNo(String taskNo, boolean withItems) {
        return queryOne("SELECT * FROM inv_stocktake_task WHERE task_no = ?", TASK, taskNo)
                .map(t -> new StocktakeTask(t.id(), t.taskNo(), t.warehouseId(), t.status(), t.totalItems(),
                        t.scannedItems(), t.diffItems(), t.operatorId(), t.operatorName(), t.finishTime(),
                        t.createTime(), withItems ? queryList(ITEM_SQL, ITEM, t.id()) : List.of()));
    }

    @Override
    public int scan(String taskNo, long skuId, int qty) {
        return update("""
                        UPDATE inv_stocktake_item i
                            JOIN inv_stocktake_task t ON t.id = i.task_id
                        SET i.actual_qty = i.actual_qty + ?
                        WHERE t.task_no = ? AND i.sku_id = ? AND t.status = 'DOING'
                        """,
                qty, taskNo, skuId);
    }

    @Override
    public void updateTotalItems(long taskId, int total) {
        update("UPDATE inv_stocktake_task SET total_items = ? WHERE id = ?", total, taskId);
    }

    @Override
    public void bumpScanned(long taskId, int diff) {
        // 已扫 SKU 数用"actual_qty > 0 的行数"实时算，
        // 而不是每次 +1：同一件扫两次时 +1 会虚高，扫码节奏一乱就对不上
        update("""
                        UPDATE inv_stocktake_task
                        SET scanned_items = (SELECT COUNT(*) FROM inv_stocktake_item WHERE task_id = ? AND actual_qty > 0),
                            diff_items    = (SELECT COUNT(*) FROM inv_stocktake_item
                                             WHERE task_id = ? AND actual_qty <> book_qty)
                        WHERE id = ?
                        """,
                taskId, taskId, taskId);
    }

    @Override
    public void updateActual(long taskId, long skuId, int actualQty) {
        update("UPDATE inv_stocktake_item SET actual_qty = ? WHERE task_id = ? AND sku_id = ?",
                actualQty, taskId, skuId);
    }

    @Override
    public List<StocktakeTask.Item> findDiffItems(long taskId) {
        return queryList("""
                        SELECT i.id, i.sku_id, s.spu_no, s.spu_name, k.size, k.color,
                               i.book_qty, i.actual_qty, i.book_version
                        FROM inv_stocktake_item i
                                 JOIN cat_sku k ON k.id = i.sku_id
                                 JOIN cat_spu s ON s.id = k.spu_id
                        WHERE i.task_id = ? AND i.actual_qty <> i.book_qty
                        ORDER BY s.spu_no, FIELD(k.size,'XS','S','M','L','XL','XXL','XXXL')
                        """, ITEM, taskId);
    }

    @Override
    public void finish(long taskId, int diffItems) {
        update("""
                        UPDATE inv_stocktake_task
                        SET status = 'DONE', finish_time = NOW(3), diff_items = ?
                        WHERE id = ? AND status = 'DOING'
                        """,
                diffItems, taskId);
    }

    @Override
    public Optional<LossCase> findLossByNo(String caseNo) {
        return queryOne("""
                        SELECT l.*, s.spu_no, s.spu_name, k.size, k.color
                        FROM inv_loss_case l
                                 JOIN cat_sku k ON k.id = l.sku_id
                                 JOIN cat_spu s ON s.id = k.spu_id
                        WHERE l.case_no = ?
                        """,
                (rs, i) -> new LossCase(rs.getLong("id"), rs.getString("case_no"), rs.getLong("sku_id"),
                        rs.getString("spu_no"), rs.getString("spu_name"), rs.getString("size"),
                        rs.getString("color"), rs.getLong("warehouse_id"), rs.getInt("qty"),
                        rs.getString("reason"), rs.getString("status"), rs.getLong("operator_id"),
                        rs.getString("operator_name"),
                        rs.getTimestamp("close_time") == null ? null
                                : rs.getTimestamp("close_time").toLocalDateTime(),
                        rs.getTimestamp("create_time").toLocalDateTime()),
                caseNo);
    }

    @Override
    public long insertLoss(String caseNo, long skuId, long warehouseId, int qty, String reason,
                           long operatorId, String operatorName) {
        return insert("""
                        INSERT INTO inv_loss_case (case_no, sku_id, warehouse_id, qty, reason, operator_id, operator_name)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                caseNo, skuId, warehouseId, qty, reason, operatorId, operatorName);
    }

    @Override
    public List<LossCase> listLoss(String status, int limit, int offset) {
        String where = status == null || status.isBlank() ? "" : " WHERE l.status = ?";
        String sql = """
                SELECT l.*, s.spu_no, s.spu_name, k.size, k.color
                FROM inv_loss_case l
                         JOIN cat_sku k ON k.id = l.sku_id
                         JOIN cat_spu s ON s.id = k.spu_id
                """ + where + " ORDER BY l.create_time DESC LIMIT ? OFFSET ?";
        RowMapper<LossCase> mapper = (rs, i) -> new LossCase(rs.getLong("id"), rs.getString("case_no"),
                rs.getLong("sku_id"), rs.getString("spu_no"), rs.getString("spu_name"), rs.getString("size"),
                rs.getString("color"), rs.getLong("warehouse_id"), rs.getInt("qty"), rs.getString("reason"),
                rs.getString("status"), rs.getLong("operator_id"), rs.getString("operator_name"),
                rs.getTimestamp("close_time") == null ? null : rs.getTimestamp("close_time").toLocalDateTime(),
                rs.getTimestamp("create_time").toLocalDateTime());
        return status == null || status.isBlank()
                ? queryList(sql, mapper, limit, offset)
                : queryList(sql, mapper, status, limit, offset);
    }

    @Override
    public void closeLoss(String caseNo) {
        update("UPDATE inv_loss_case SET status = 'CLOSED', close_time = NOW(3) WHERE case_no = ? AND status = 'OPEN'",
                caseNo);
    }

    @Override
    public int countOpenLoss() {
        return queryInt("SELECT COUNT(*) FROM inv_loss_case WHERE status = 'OPEN'");
    }
}
