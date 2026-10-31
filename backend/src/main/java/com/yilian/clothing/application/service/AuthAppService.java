package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.Role;
import com.yilian.clothing.domain.model.UserAccount;
import com.yilian.clothing.domain.repository.SupportRepository;
import com.yilian.clothing.infrastructure.cache.RateLimiter;
import com.yilian.clothing.infrastructure.security.AuthContext;
import com.yilian.clothing.infrastructure.security.JwtService;
import com.yilian.clothing.infrastructure.security.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 登录用例（Q66/Q67：手机号 + 密码）。
 *
 * <p>安全细节：
 * <ol>
 *   <li>手机号不存在与密码错误返回**同一个错误**（4001），否则等于给攻击者一个"手机号是否存在"的探测器；</li>
 *   <li>连续 5 次失败锁定 10 分钟（Redis 计数），挡住撞库；</li>
 *   <li>登录成功清零计数，避免"白天输错两次、晚上就被锁"的误伤；</li>
 *   <li>限流维度用手机号而不是 IP：门店所有人共用一个 WiFi 出口 IP，按 IP 限流会互相误伤。</li>
 * </ol></p>
 */
@Service
public class AuthAppService {

    private final SupportRepository supportRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redis;

    @Value("${app.security.login-max-fail:5}")
    private int maxFail;

    @Value("${app.security.login-lock-seconds:600}")
    private long lockSeconds;

    public AuthAppService(SupportRepository supportRepository, PasswordHasher passwordHasher,
                          JwtService jwtService, RateLimiter rateLimiter, StringRedisTemplate redis) {
        this.supportRepository = supportRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.redis = redis;
    }

    public LoginResult login(String phone, String rawPassword) {
        if (!rateLimiter.allow("login:" + phone, 10, 0.2)) {
            throw BizException.of(ResultCode.RATE_LIMITED);
        }
        String failKey = "login:fail:" + phone;
        String fails = redis.opsForValue().get(failKey);
        if (fails != null && Integer.parseInt(fails) >= maxFail) {
            throw BizException.of(ResultCode.LOGIN_LOCKED);
        }

        UserAccount user = supportRepository.findUserByPhone(phone)
                .orElseThrow(() -> {
                    bumpFail(failKey);
                    return BizException.of(ResultCode.LOGIN_FAILED);
                });
        if (!passwordHasher.verify(rawPassword, user.passwordHash())) {
            bumpFail(failKey);
            throw BizException.of(ResultCode.LOGIN_FAILED);
        }
        if (!user.enabled()) {
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }
        redis.delete(failKey);

        Set<String> perms = supportRepository.findPermissions(user.role().name());
        String token = jwtService.issue(user.id(), user.phone(), user.nickname(), user.role(), user.shopId());
        return new LoginResult(token, jwtService.ttlSeconds(), toView(user, perms));
    }

    public UserView me() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        UserAccount user = supportRepository.findUserByPhone(op.phone())
                .orElseThrow(() -> BizException.of(ResultCode.UNAUTHORIZED));
        return toView(user, op.perms());
    }

    private void bumpFail(String key) {
        Long v = redis.opsForValue().increment(key);
        if (v != null && v == 1L) {
            redis.expire(key, Duration.ofSeconds(lockSeconds));
        }
    }

    private UserView toView(UserAccount user, Set<String> perms) {
        return new UserView(user.id(), user.phone(), user.nickname(), user.role().name(),
                user.role().label(), user.shopId(), user.shopName(), user.shopAddress(),
                perms.stream().sorted().toList());
    }

    /**
     * @param permissions 权限码列表。前端据此决定"显示哪些菜单"，
     *                    但**真正的拦截在服务端**——前端隐藏只是体验，不是安全（ADR-06）。
     */
    public record LoginResult(String token, long expiresIn, UserView user) {
    }

    public record UserView(Long id, String phone, String nickname, String role, String roleLabel,
                           Long shopId, String shopName, String shopAddress, java.util.List<String> permissions) {
        public boolean isOwner() {
            return Role.OWNER.name().equals(role);
        }
    }
}
