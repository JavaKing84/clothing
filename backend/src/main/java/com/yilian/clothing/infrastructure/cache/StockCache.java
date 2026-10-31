package com.yilian.clothing.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 库存读缓存（ADR-07）。
 *
 * <p>核心设计：缓存 key 里带一个**全局代际号** {@code g{gen}}。
 * 任何库存写操作只做一次 {@code INCR stock:gen}，旧代际的 key 自然过期。
 * 相比 {@code SCAN + DEL}（会阻塞 Redis 单线程）或 {@code KEYS}（生产禁用），
 * 这是 O(1) 且集群安全的失效方式。</p>
 *
 * <p>代价：旧 key 会白占内存到 TTL 到期。所以 TTL 设 60s——足够短，不会堆积。</p>
 */
@Component
public class StockCache {

    private static final String GEN_KEY = "stock:gen";
    private static final Duration MATRIX_TTL = Duration.ofSeconds(60);
    private static final Duration ALERT_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public StockCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public long generation() {
        String v = redis.opsForValue().get(GEN_KEY);
        if (v == null) {
            // setIfAbsent 而不是 set：并发时不要互相覆盖代际号
            redis.opsForValue().setIfAbsent(GEN_KEY, "1");
            return 1L;
        }
        return Long.parseLong(v);
    }

    /** 库存写操作后调用。一次 INCR = 全站库存缓存失效。 */
    public void bumpGeneration() {
        redis.opsForValue().increment(GEN_KEY);
    }

    public String matrixKey(long warehouseId, String filterSignature) {
        return "stock:matrix:" + warehouseId + ":" + filterSignature + ":g" + generation();
    }

    public String alertKey(long warehouseId) {
        return "stock:alerts:" + warehouseId + ":g" + generation();
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            // 缓存永远是"尽力而为"：Redis 挂了、JSON 结构变了，都必须退化成查库，
            // 绝不能让缓存异常把业务请求打成 500
            return Optional.empty();
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ignored) {
            // 同上：写缓存失败不影响正确性
        }
    }

    public void putMatrix(String key, Object value) {
        put(key, value, MATRIX_TTL);
    }

    public void putAlerts(String key, Object value) {
        put(key, value, ALERT_TTL);
    }
}
