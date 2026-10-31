package com.yilian.clothing.domain.model;

/**
 * 角色。只有两种，《答卷》Q13 说得很清楚：店主 2 人、店员 2 人。
 *
 * <p>为什么不用"角色继承"（OWNER extends CLERK）：只有两个角色时继承是过度设计，
 * 且会让权限判断变成"猜继承链"。权限矩阵放表里（sys_role_permission），
 * 角色只是矩阵的 key。</p>
 */
public enum Role {
    OWNER("店主"),
    CLERK("店员");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Role of(String value) {
        for (Role r : values()) {
            if (r.name().equalsIgnoreCase(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知角色: " + value);
    }
}
