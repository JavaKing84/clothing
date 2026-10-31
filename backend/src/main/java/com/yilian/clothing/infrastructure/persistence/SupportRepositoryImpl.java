package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.domain.model.OutboxMessage;
import com.yilian.clothing.domain.model.ReconcileBatch;
import com.yilian.clothing.domain.model.Role;
import com.yilian.clothing.domain.model.UserAccount;
import com.yilian.clothing.domain.model.Warehouse;
import com.yilian.clothing.domain.repository.SupportRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 支撑域仓储实现：账号、仓、发号器、幂等令牌、发件箱、离线对账。
 */
@Repository
public class SupportRepositoryImpl extends JdbcSupport implements SupportRepository {

    public SupportRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<UserAccount> findUserByPhone(String phone) {
        return queryOne("""
                        SELECT u.id, u.phone, u.password, u.nickname, u.role, u.shop_id, u.status,
                               s.shop_name, s.address
                        FROM sys_user u JOIN sys_shop s ON s.id = u.shop_id
                        WHERE u.phone = ? AND u.del_flag = 0
                        """,
                (rs, i) -> new UserAccount(rs.getLong("id"), rs.getString("phone"), rs.getString("password"),
                        rs.getString("nickname"), Role.of(rs.getString("role")), rs.getLong("shop_id"),
                        rs.getInt("status"), rs.getString("shop_name"), rs.getString("address")),
                phone);
    }

    @Override
    public Set<String> findPermissions(String role) {
        List<String> codes = queryList("SELECT perm_code FROM sys_role_permission WHERE role = ?",
                (rs, i) -> rs.getString(1), role);
        return new HashSet<>(codes);
    }

    @Override
    public List<UserAccount> listUsers(long shopId) {
        return queryList("""
                        SELECT id, phone, nickname, role, shop_id, status
                        FROM sys_user WHERE shop_id = ? AND del_flag = 0 ORDER BY role DESC, id
                        """,
                (rs, i) -> new UserAccount(rs.getLong("id"), rs.getString("phone"), "",
                        rs.getString("nickname"), Role.of(rs.getString("role")), rs.getLong("shop_id"),
                        rs.getInt("status"), null, null),
                shopId);
    }

    @Override
    public List<Warehouse> listWarehouses(long shopId) {
        return queryList("""
                        SELECT id, warehouse_no, warehouse_name, is_default
                        FROM sys_warehouse WHERE shop_id = ? AND del_flag = 0 ORDER BY is_default DESC, id
                        """,
                (rs, i) -> new Warehouse(rs.getLong("id"), rs.getString("warehouse_no"),
                        rs.getString("warehouse_name"), rs.getInt("is_default") == 1),
                shopId);
    }

    /**
     * 按日发号：先 UPDATE 自增，再 SELECT 回来。
     *
     * <p>为什么不用 {@code LAST_INSERT_ID(cur_val+1)} 的技巧：那个函数是**连接级**的，
     * 一旦 JdbcTemplate 从连接池换了连接（没有事务时就会），读回来的就是别的连接的值。
     * 这里改成"UPDATE 再加行锁读回"，只要调用方在事务里，读回的一定是本事务的值。</p>
     *
     * <p>代价：同一时刻所有开单请求串行在这张表的一行上。3-5 人的店，这是几微秒的事；
     * 真要高并发，应改成号段模式（一次取 100 个号缓存在内存）——本项目的量级不需要，
     * 但在讲义里必须能说清这个演进方向。</p>
     */
    @Override
    public long nextSeq(String biz) {
        int rows = update("UPDATE sys_seq SET cur_val = cur_val + 1 WHERE biz = ? AND seq_date = CURDATE()", biz);
        if (rows == 0) {
            try {
                update("INSERT INTO sys_seq (biz, seq_date, cur_val) VALUES (?, CURDATE(), 1)", biz);
            } catch (DuplicateKeyException race) {
                // 两个线程同时发现"没有今天的行"，一个插入成功，另一个撞主键后补一次自增
                update("UPDATE sys_seq SET cur_val = cur_val + 1 WHERE biz = ? AND seq_date = CURDATE()", biz);
            }
        }
        return queryLong("SELECT cur_val FROM sys_seq WHERE biz = ? AND seq_date = CURDATE()", biz);
    }

    @Override
    public Optional<String> findScanTokenResult(String token) {
        return queryOne("SELECT result_json FROM inv_scan_token WHERE token = ? AND expire_at > NOW(3)",
                (rs, i) -> rs.getString(1), token);
    }

    @Override
    public void saveScanToken(String token, Long skuId, String resultJson, LocalDateTime expireAt) {
        try {
            update("INSERT INTO inv_scan_token (token, sku_id, result_json, expire_at) VALUES (?, ?, ?, ?)",
                    token, skuId, resultJson, expireAt);
        } catch (DuplicateKeyException ignored) {
            // 令牌已存在说明并发的另一个请求先写完了，什么都不用做
        }
    }

    @Override
    public void appendOutbox(String bizNo, String msgType, String exchange, String routingKey, String payload) {
        try {
            update("""
                            INSERT INTO msg_outbox (biz_no, msg_type, exchange, routing_key, payload, status,
                                                    next_retry_at)
                            VALUES (?, ?, ?, ?, ?, 0, NOW(3))
                            """,
                    bizNo, msgType, exchange, routingKey, payload);
        } catch (DuplicateKeyException ignored) {
            // uk_biz_msg 保证同一业务单同一消息只投一次
        }
    }

    @Override
    public List<OutboxMessage> claimOutbox(int limit) {
        // SKIP LOCKED：多个实例同时轮询时，各自拿各自的行，不互相等我。
        // 没有它，两个实例会锁同一批行，一个干一个等，吞吐直接砍半。
        List<OutboxMessage> candidates = queryList("""
                        SELECT id, biz_no, msg_type, exchange, routing_key, payload, status, retry_count, next_retry_at
                        FROM msg_outbox
                        WHERE status IN (0, 2) AND next_retry_at <= NOW(3)
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, i) -> new OutboxMessage(rs.getLong("id"), rs.getString("biz_no"), rs.getString("msg_type"),
                        rs.getString("exchange"), rs.getString("routing_key"), rs.getString("payload"),
                        rs.getInt("status"), rs.getInt("retry_count"),
                        rs.getTimestamp("next_retry_at").toLocalDateTime()),
                limit);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Object> ids = new ArrayList<>(candidates.stream().map(OutboxMessage::id).toList());
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        update("UPDATE msg_outbox SET status = 8 WHERE id IN (" + placeholders + ")", ids.toArray());
        return candidates;
    }

    @Override
    public void markOutboxSent(long id) {
        update("UPDATE msg_outbox SET status = 1 WHERE id = ?", id);
    }

    @Override
    public void markOutboxFailed(long id, int retryCount, LocalDateTime nextRetryAt) {
        // 重试 5 次仍失败 → 死信，等人工处理。绝不能无限重试，
        // 否则一条坏消息会把整个发件箱队列堵死。
        int status = retryCount >= 5 ? 3 : 2;
        update("UPDATE msg_outbox SET status = ?, retry_count = ?, next_retry_at = ? WHERE id = ?",
                status, retryCount, nextRetryAt, id);
    }

    @Override
    public boolean tryConsume(String bizNo, String msgType) {
        try {
            update("INSERT INTO msg_consumed (biz_no, msg_type) VALUES (?, ?)", bizNo, msgType);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public long insertReconcileBatch(String batchNo, String deviceId, int total) {
        return insert("""
                        INSERT INTO scan_reconcile_batch (batch_no, device_id, total)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE total = VALUES(total)
                        """,
                batchNo, deviceId, total);
    }

    @Override
    public void insertReconcileItem(long batchId, ReconcileBatch.Item item) {
        try {
            update("""
                            INSERT INTO scan_reconcile_item (batch_id, client_token, device_seq, barcode, scan_type,
                                                             quantity, result, flow_no, reason)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    batchId, item.clientToken(), item.deviceSeq(), item.barcode(), item.scanType(),
                    item.quantity(), item.result().name(), item.flowNo(), item.reason());
        } catch (DuplicateKeyException ignored) {
            // uk_client_token：同一条离线记录重复上传，只记第一次的裁决结果
        }
    }

    @Override
    public void updateReconcileCounters(long batchId, int accepted, int duplicated, int conflicted, int rejected) {
        update("""
                        UPDATE scan_reconcile_batch
                        SET accepted = ?, duplicated = ?, conflicted = ?, rejected = ?
                        WHERE id = ?
                        """,
                accepted, duplicated, conflicted, rejected, batchId);
    }

    @Override
    public Optional<ReconcileBatch> findReconcileBatch(String batchNo) {
        Optional<ReconcileBatch> batch = queryOne("SELECT * FROM scan_reconcile_batch WHERE batch_no = ?",
                (rs, i) -> new ReconcileBatch(rs.getLong("id"), rs.getString("batch_no"), rs.getString("device_id"),
                        rs.getInt("total"), rs.getInt("accepted"), rs.getInt("duplicated"),
                        rs.getInt("conflicted"), rs.getInt("rejected"),
                        rs.getTimestamp("create_time").toLocalDateTime(), List.of()),
                batchNo);
        return batch.map(b -> new ReconcileBatch(b.id(), b.batchNo(), b.deviceId(), b.total(), b.accepted(),
                b.duplicated(), b.conflicted(), b.rejected(), b.createTime(),
                queryList("""
                                SELECT id, client_token, device_seq, barcode, scan_type, quantity, result,
                                       flow_no, reason
                                FROM scan_reconcile_item WHERE batch_id = ? ORDER BY device_seq
                                """,
                        (rs, i) -> new ReconcileBatch.Item(rs.getLong("id"), rs.getString("client_token"),
                                rs.getLong("device_seq"), rs.getString("barcode"), rs.getInt("scan_type"),
                                rs.getInt("quantity"),
                                ReconcileBatch.Result.valueOf(rs.getString("result")),
                                rs.getString("flow_no"), rs.getString("reason")),
                        b.id())));
    }

    @Override
    public List<long[]> stockSnapshot(long warehouseId) {
        return queryList("SELECT sku_id, quantity FROM inv_sku_stock WHERE warehouse_id = ? AND del_flag = 0",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, warehouseId);
    }

    @Override
    public String maxCatalogVersion() {
        return queryOne("""
                        SELECT DATE_FORMAT(MAX(t), '%Y-%m-%d %H:%i:%s.%f')
                        FROM (SELECT update_time AS t FROM inv_sku_stock WHERE del_flag = 0
                              UNION ALL SELECT update_time FROM cat_spu WHERE del_flag = 0
                              UNION ALL SELECT update_time FROM cat_sku WHERE del_flag = 0) x
                        """,
                (rs, i) -> rs.getString(1)).orElse("1970-01-01 00:00:00.000000");
    }

    /** 未使用，保留给将来的"用户列表"接口（Q6/Q13 员工管理） */
    private static final RowMapper<UserAccount> USER_MAPPER = (rs, i) -> new UserAccount(
            rs.getLong("id"), rs.getString("phone"), rs.getString("password"), rs.getString("nickname"),
            Role.of(rs.getString("role")), rs.getLong("shop_id"), rs.getInt("status"), null, null);
}
