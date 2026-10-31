package com.yilian.clothing.domain.model;

/**
 * 用户账号（含密码哈希）。这个对象**只在登录链路里出现**，
 * 绝不能被 Controller 直接序列化返回——密码哈希泄漏比明文更隐蔽。
 */
public record UserAccount(
        Long id,
        String phone,
        String passwordHash,
        String nickname,
        Role role,
        Long shopId,
        int status,
        String shopName,
        String shopAddress
) {
    public boolean enabled() {
        return status == 1;
    }
}
