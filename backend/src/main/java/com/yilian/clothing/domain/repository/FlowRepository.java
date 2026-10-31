package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.StockFlow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 流水仓储端口。账本只追加，因此没有 update(StockFlow) 这种危险方法。 */
public interface FlowRepository {

    long append(StockFlow flow);

    Optional<StockFlow> findByFlowNo(String flowNo);

    Optional<StockFlow> findByScanToken(String scanToken);

    /**
     * 幂等令牌的**当前读**（{@code FOR SHARE}）。
     *
     * <p>并发撞车时必须是当前读：隔离级别是 REPEATABLE READ，普通一致性读用的是事务开始时的快照，
     * 而撞车的那条流水是另一个事务刚刚提交的——快照里根本没有它，
     * 于是"唯一索引报重复，却查不到那一行"，只能把 500 抛给店员。
     * {@code FOR SHARE} 拿的是最新已提交版本，能看见。</p>
     */
    Optional<StockFlow> findByScanTokenCurrent(String scanToken);

    Optional<StockFlow> findByClientToken(String clientToken);

    /** 标记原流水已撤销（status=2）。只改状态，不改数量。 */
    void markReversed(String flowNo);

    List<StockFlow> query(Long warehouseId, FlowType type, String spuNo, Long operatorId,
                          LocalDateTime from, LocalDateTime to, int limit, int offset, boolean withCost);

    /** 某业务单下的全部未撤销流水（撤单时逐条冲正，而不是凭记忆算一个总数） */
    List<StockFlow> listActiveByBiz(String bizNo);

    long count(Long warehouseId, FlowType type, String spuNo, Long operatorId,
               LocalDateTime from, LocalDateTime to);
}
