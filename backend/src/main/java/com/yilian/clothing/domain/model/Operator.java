package com.yilian.clothing.domain.model;

import java.util.Set;

/**
 * 当前操作人（从 JWT 解出 + 权限码从库/缓存补齐）。
 *
 * @param id       用户 ID
 * @param phone    手机号
 * @param nickname 姓名，写流水时冗余进去（店员离职后流水仍要显示"谁做的"）
 * @param role     角色
 * @param shopId   门店
 * @param perms    权限码集合，登录时一次性加载
 */
public record Operator(Long id, String phone, String nickname, Role role, Long shopId, Set<String> perms) {

    public boolean can(Permission p) {
        return perms.contains(p.code());
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }

    /** 成本/利润字段是否可见。所有价格脱敏都必须走这一个判断，避免三处各写一遍。 */
    public boolean canSeeCost() {
        return can(Permission.STOCK_COST_READ);
    }
}
