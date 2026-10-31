package com.yilian.clothing.infrastructure.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 令牌桶限流（Redis + Lua）。
 *
 * <p>为什么不用固定窗口（"每分钟最多 60 次"）：固定窗口在窗口边界会放过 2 倍流量
 * （59 秒 60 次 + 61 秒 60 次 = 2 秒内 120 次）。令牌桶按时间连续补充，
 * 没有边界突刺，且允许短时突发。</p>
 *
 * <p>Lua 的必要性：读令牌数 → 判断 → 扣令牌 三步必须原子。
 * 拆成三次 Redis 调用，在高并发下会集体读到"还有 1 个令牌"然后一起扣成负数。</p>
 */
@Component
public class RateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local refill   = tonumber(ARGV[2])
            local now      = tonumber(ARGV[3])
            local ttl      = tonumber(ARGV[4])
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            local delta = math.max(0, now - ts) / 1000.0
            tokens = math.min(capacity, tokens + delta * refill)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', KEYS[1], ttl)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @param key     限流维度（登录用手机号，扫码用 user:device）
     * @param capacity 桶容量（允许的突发量）
     * @param refillPerSecond 每秒补充令牌数
     */
    public boolean allow(String key, int capacity, double refillPerSecond) {
        try {
            Long ok = redis.execute(SCRIPT, List.of("rl:" + key),
                    String.valueOf(capacity), String.valueOf(refillPerSecond),
                    String.valueOf(System.currentTimeMillis()), "120");
            return ok == null || ok == 1L;
        } catch (Exception e) {
            // 限流器故障时放行：宁可被打，也不要全员登录不了
            return true;
        }
    }
}
