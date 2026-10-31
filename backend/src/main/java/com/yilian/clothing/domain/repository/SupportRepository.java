package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.OutboxMessage;
import com.yilian.clothing.domain.model.ReconcileBatch;
import com.yilian.clothing.domain.model.UserAccount;
import com.yilian.clothing.domain.model.Warehouse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 支撑域端口：账号、仓、单号发号器、幂等令牌、发件箱、离线对账。
 *
 * <p>这些不是"业务"，但都是业务正确性的基础设施，混在业务仓储里会让业务仓储失去焦点，
 * 因此单独一个端口。</p>
 */
public interface SupportRepository {

    Optional<UserAccount> findUserByPhone(String phone);

    Set<String> findPermissions(String role);

    List<Warehouse> listWarehouses(long shopId);

    /** 员工列表（Q6/Q13）。密码哈希不参与查询，避免它出现在任何响应里。 */
    List<UserAccount> listUsers(long shopId);

    /** 按日自增单号：UPDATE ... LAST_INSERT_ID(cur_val+1) 拿回本次值（行锁串行化）。 */
    long nextSeq(String biz);

    Optional<String> findScanTokenResult(String token);

    void saveScanToken(String token, Long skuId, String resultJson, LocalDateTime expireAt);

    void appendOutbox(String bizNo, String msgType, String exchange, String routingKey, String payload);

    /** FOR UPDATE SKIP LOCKED 抢占待投递消息，返回本批要给谁。 */
    List<OutboxMessage> claimOutbox(int limit);

    void markOutboxSent(long id);

    void markOutboxFailed(long id, int retryCount, LocalDateTime nextRetryAt);

    /** 消费端幂等：第一次消费返回 true，重复消费返回 false（撞主键）。 */
    boolean tryConsume(String bizNo, String msgType);

    long insertReconcileBatch(String batchNo, String deviceId, int total);

    void insertReconcileItem(long batchId, ReconcileBatch.Item item);

    void updateReconcileCounters(long batchId, int accepted, int duplicated, int conflicted, int rejected);

    Optional<ReconcileBatch> findReconcileBatch(String batchNo);

    /** 离线库存快照：返回 [skuId, quantity] 全量，客户端断网前预热。 */
    List<long[]> stockSnapshot(long warehouseId);

    String maxCatalogVersion();
}
