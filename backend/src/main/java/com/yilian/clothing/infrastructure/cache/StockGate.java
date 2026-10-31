package com.yilian.clothing.infrastructure.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 预扣闸门（ADR-04 第 3 层）。
 *
 * <h3>它解决什么问题</h3>
 * <p>行锁方案是正确的，但"正确"不等于"便宜"：10 个店员同时抢最后 1 件，
 * 9 个人会先在数据库排队、拿到锁、发现不足、回滚——9 次无意义的行锁往返。
 * 闸门在 Redis 里先挡掉这 9 个人，只放 1 个进数据库。</p>
 *
 * <h3>它为什么不会让库存算错</h3>
 * <p>三个铁律：
 * <ol>
 *   <li><b>DB 是唯一事实源</b>。闸门只"预扣"，不记账。</li>
 *   <li><b>每次 DB 写成功后，用 DB 的真实余量覆盖 Redis</b>（{@link #reset}），
 *       所以偏差最多存活一个请求，不会累积。</li>
 *   <li><b>闸门异常一律放行</b>（fail-open）：Redis 挂了就退化成纯 DB 行锁，
 *       系统变慢但不会算错。反过来（fail-close）会让 Redis 成为单点。</li>
 * </ol></p>
 *
 * <p>这就是"缓存不能当事实源"的具体落地方式，也是讲义里反复强调的那句话：
 * <b>可以为了性能牺牲延迟，绝不能为了性能牺牲正确性。</b></p>
 */
@Component
public class StockGate {

    /** 返回 -1 表示"未预热"，调用方应直接查库；-2 表示"已扣减，放行"；>=0 表示"剩余量，不足" */
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return -1 end
            local avail = tonumber(v)
            local need = tonumber(ARGV[1])
            if avail < need then return avail end
            redis.call('DECRBY', KEYS[1], need)
            return -2
            """, Long.class);

    private final StringRedisTemplate redis;

    public StockGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(long warehouseId, long skuId) {
        return "stock:avail:" + warehouseId + ":" + skuId;
    }

    public enum Decision {
        /** 放行（Redis 已预扣，或闸门未预热由 DB 裁决） */
        PASS,
        /** 明确不足，remainQty 是 Redis 里的余量 */
        SHORT
    }

    public record Result(Decision decision, int remainQty) {
    }

    public Result tryAcquire(long warehouseId, long skuId, int qty) {
        try {
            Long r = redis.execute(ACQUIRE, List.of(key(warehouseId, skuId)), String.valueOf(qty));
            if (r == null) {
                return new Result(Decision.PASS, -1);
            }
            if (r == -1L || r == -2L) {
                return new Result(Decision.PASS, -1);
            }
            return new Result(Decision.SHORT, r.intValue());
        } catch (Exception e) {
            // fail-open：Redis 不可用时不能挡住生意
            return new Result(Decision.PASS, -1);
        }
    }

    /** 用 DB 的真实余量覆盖闸门（写成功后调用，自我纠偏） */
    public void reset(long warehouseId, long skuId, int quantity) {
        try {
            redis.opsForValue().set(key(warehouseId, skuId), String.valueOf(quantity), Duration.ofSeconds(300));
        } catch (Exception ignored) {
        }
    }

    /** 预热：只在 key 不存在时写入，避免把别的实例刚扣过的值覆盖回去 */
    public void warmUp(long warehouseId, long skuId, int quantity) {
        try {
            redis.opsForValue().setIfAbsent(key(warehouseId, skuId), String.valueOf(quantity),
                    Duration.ofSeconds(300));
        } catch (Exception ignored) {
        }
    }

    /** DB 事务失败时回补预扣 */
    public void refund(long warehouseId, long skuId, int qty) {
        try {
            redis.opsForValue().increment(key(warehouseId, skuId), qty);
        } catch (Exception ignored) {
        }
    }
}
