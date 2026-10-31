package com.yilian.clothing.infrastructure.security;

import com.yilian.clothing.domain.model.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验。
 *
 * <p>为什么用 JWT 而不是服务端 Session：门店用手机，网络会切（WiFi ↔ 4G），
 * Session 需要粘性会话或集中式 Session 存储；JWT 自带身份，任意实例都能验。
 * 代价是**无法主动吊销**——所以 TTL 设 24 小时，且不把权限码放进 token，
 * 而是每次请求从库里取（或从缓存取）。这样"店员降级为离职"能立刻生效，
 * 不用等 token 过期。这是"无状态"与"可撤销"的经典权衡。</p>
 *
 * <p>HS256 的密钥必须 ≥ 32 字节，否则 jjwt 直接抛 WeakKeyException——
 * 这是库在替我们挡住"用了弱密钥还以为很安全"的错误。</p>
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(long userId, String phone, String nickname, Role role, long shopId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("phone", phone)
                .claim("nickname", nickname)
                .claim("role", role.name())
                .claim("shopId", shopId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并验签。签名不对、过期、格式错都会抛 JwtException，
     * 由过滤器统一翻译成 401，不在这里吞异常——吞掉会让"过期"和"伪造"无法区分。
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
