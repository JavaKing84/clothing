package com.yilian.clothing.infrastructure.security;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希：PBKDF2-HMAC-SHA256，21 万次迭代，每人独立 16 字节盐。
 *
 * <h3>为什么不用 BCrypt / Argon2</h3>
 * <p>两者都更强（尤其抗 GPU），但它们都需要第三方依赖：BCrypt 要 spring-security-crypto，
 * Argon2 要 BouncyCastle。本项目刻意把依赖压到最少（OCR 只看代码，多一个 jar 就多一个"这是什么"的问题）。
 * PBKDF2 是 JDK 自带的 {@code PBKDF2WithHmacSHA256}，且被 NIST SP 800-63B 接受。
 * 21 万次迭代是 OWASP 2023 对 PBKDF2-SHA256 的推荐值。</p>
 *
 * <h3>为什么不用 MD5/SHA256 裸哈希</h3>
 * <p>裸哈希在同一密码下产生同一摘要，一张彩虹表就能反查"123456"。
 * 盐的作用是让相同密码产生不同摘要；迭代的作用是让每次猜测都变慢。</p>
 *
 * <p>存储格式：{@code pbkdf2_sha256$210000$<salt-base64>$<hash-base64>}。
 * 把算法与迭代数写进字符串，是为了将来升级迭代数时能"验旧密码、按新参数重存"，
 * 而不是把所有用户踢下线强制改密。</p>
 */
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(dk);
    }

    /**
     * 校验密码。任何格式异常都返回 false 而不是抛异常——
     * 登录接口不该因为"库里有一条脏数据"就 500。
     */
    public boolean verify(String rawPassword, String stored) {
        if (stored == null || rawPassword == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword, salt, iterations);
            // 必须用常量时间比较：普通 equals 会在第一个不同字节处返回，
            // 攻击者能通过计时差逐字节猜出哈希（时序侧信道）
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] pbkdf2(String raw, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw BizException.of(ResultCode.SYSTEM_ERROR, "密码哈希计算失败");
        }
    }
}
