package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.domain.model.FlowType;
import com.yilian.clothing.domain.model.StockFlow;
import com.yilian.clothing.domain.repository.FlowRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 流水仓储实现。
 *
 * <p>{@code uk_scan_token} 唯一索引让"重复提交"在存储层失败。
 * 这里**不**把 DuplicateKeyException 吞掉——吞掉会让上层误以为写成功。
 * 由应用层捕获并翻译成"返回首次结果"，语义更清楚。</p>
 */
@Repository
public class FlowRepositoryImpl extends JdbcSupport implements FlowRepository {

    public FlowRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    private static final RowMapper<StockFlow> MAPPER = (rs, i) -> new StockFlow(
            rs.getLong("id"),
            rs.getString("flow_no"),
            FlowType.of(rs.getInt("flow_type")),
            rs.getLong("sku_id"),
            rs.getLong("spu_id"),
            rs.getString("spu_no"),
            rs.getString("spu_name"),
            rs.getString("size"),
            rs.getString("color"),
            rs.getLong("warehouse_id"),
            rs.getInt("quantity"),
            rs.getInt("signed_qty"),
            rs.getInt("before_qty"),
            rs.getInt("after_qty"),
            rs.getBigDecimal("sale_price"),
            rs.getBigDecimal("purchase_price"),
            rs.getString("pay_type"),
            rs.getLong("operator_id"),
            rs.getString("operator_name"),
            rs.getString("biz_no"),
            rs.getString("scan_token"),
            rs.getString("client_token"),
            rs.getInt("status"),
            rs.getString("revert_flow_no"),
            rs.getString("remark"),
            rs.getTimestamp("create_time").toLocalDateTime()
    );

    @Override
    public long append(StockFlow f) {
        return insert("""
                        INSERT INTO inv_stock_flow (flow_no, flow_type, sku_id, spu_id, spu_no, spu_name, size, color,
                                                    warehouse_id, quantity, signed_qty, before_qty, after_qty,
                                                    sale_price, purchase_price, pay_type, operator_id, operator_name,
                                                    biz_no, scan_token, client_token, status, revert_flow_no, remark,
                                                    create_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                f.flowNo(), f.flowType().code(), f.skuId(), f.spuId(), f.spuNo(), f.spuName(), f.size(), f.color(),
                f.warehouseId(), f.quantity(), f.signedQty(), f.beforeQty(), f.afterQty(),
                f.salePrice(), f.purchasePrice(), f.payType(), f.operatorId(), f.operatorName(),
                f.bizNo(), f.scanToken(), f.clientToken(), f.status(), f.revertFlowNo(), f.remark(),
                f.createTime());
    }

    @Override
    public Optional<StockFlow> findByFlowNo(String flowNo) {
        return queryOne("SELECT * FROM inv_stock_flow WHERE flow_no = ?", MAPPER, flowNo);
    }

    @Override
    public Optional<StockFlow> findByScanToken(String scanToken) {
        return queryOne("SELECT * FROM inv_stock_flow WHERE scan_token = ?", MAPPER, scanToken);
    }

    @Override
    public Optional<StockFlow> findByScanTokenCurrent(String scanToken) {
        // FOR SHARE = 当前读，能看见并发事务刚提交的那一行（快照读看不见，见端口注释）
        return queryOne("SELECT * FROM inv_stock_flow WHERE scan_token = ? FOR SHARE", MAPPER, scanToken);
    }

    @Override
    public Optional<StockFlow> findByClientToken(String clientToken) {
        return queryOne("SELECT * FROM inv_stock_flow WHERE client_token = ?", MAPPER, clientToken);
    }

    @Override
    public void markReversed(String flowNo) {
        int rows = update("UPDATE inv_stock_flow SET status = 2 WHERE flow_no = ? AND status = 1", flowNo);
        if (rows != 1) {
            throw new IllegalStateException("流水不存在或已撤销: " + flowNo);
        }
    }

    @Override
    public List<StockFlow> query(Long warehouseId, FlowType type, String spuNo, Long operatorId,
                                 LocalDateTime from, LocalDateTime to, int limit, int offset, boolean withCost) {
        StringBuilder sql = new StringBuilder("SELECT * FROM inv_stock_flow WHERE 1 = 1");
        List<Object> args = buildFilters(sql, warehouseId, type, spuNo, operatorId, from, to);
        sql.append(" ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        List<StockFlow> flows = queryList(sql.toString(), MAPPER, args.toArray());
        if (withCost) {
            return flows;
        }
        // 店员视图：进货价整列抹掉。这里用"再映射一次"而不是 SQL 里写 NULL，
        // 是因为 SELECT * 的写法在报表导出等路径上复用度更高，抹零集中在一处更好审计。
        return flows.stream().map(f -> new StockFlow(f.id(), f.flowNo(), f.flowType(), f.skuId(), f.spuId(),
                f.spuNo(), f.spuName(), f.size(), f.color(), f.warehouseId(), f.quantity(), f.signedQty(),
                f.beforeQty(), f.afterQty(), f.salePrice(), null, f.payType(), f.operatorId(), f.operatorName(),
                f.bizNo(), f.scanToken(), f.clientToken(), f.status(), f.revertFlowNo(), f.remark(),
                f.createTime())).toList();
    }

    @Override
    public List<StockFlow> listActiveByBiz(String bizNo) {
        return queryList("SELECT * FROM inv_stock_flow WHERE biz_no = ? AND status = 1 ORDER BY id",
                MAPPER, bizNo);
    }

    @Override
    public long count(Long warehouseId, FlowType type, String spuNo, Long operatorId,
                      LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM inv_stock_flow WHERE 1 = 1");
        List<Object> args = buildFilters(sql, warehouseId, type, spuNo, operatorId, from, to);
        return queryLong(sql.toString(), args.toArray());
    }

    private List<Object> buildFilters(StringBuilder sql, Long warehouseId, FlowType type, String spuNo,
                                      Long operatorId, LocalDateTime from, LocalDateTime to) {
        List<Object> args = new ArrayList<>();
        if (warehouseId != null) {
            sql.append(" AND warehouse_id = ?");
            args.add(warehouseId);
        }
        if (type != null) {
            sql.append(" AND flow_type = ?");
            args.add(type.code());
        }
        if (spuNo != null && !spuNo.isBlank()) {
            sql.append(" AND spu_no LIKE ?");
            args.add("%" + spuNo.trim() + "%");
        }
        if (operatorId != null) {
            sql.append(" AND operator_id = ?");
            args.add(operatorId);
        }
        if (from != null) {
            sql.append(" AND create_time >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND create_time < ?");
            args.add(to);
        }
        return args;
    }
}
