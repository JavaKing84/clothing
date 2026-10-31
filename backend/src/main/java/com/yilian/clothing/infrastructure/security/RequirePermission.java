package com.yilian.clothing.infrastructure.security;

import com.yilian.clothing.domain.model.Permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限声明。
 *
 * <p>用法：{@code @RequirePermission(Permission.REPORT_PROFIT_READ)}。
 * 注解参数只能是常量，所以这里用 {@link Permission} 枚举的 name()，
 * 由 {@code PermissionInterceptor} 反查成权限码——写错枚举名编译不过。</p>
 *
 * <p>为什么不用 Spring Security 的 {@code @PreAuthorize("hasAuthority('...')")}：
 * 那是一段 SpEL 字符串，写错要到运行时才发现，而且权限来源（UserDetails）藏在
 * 一堆 Provider 里。150 行自研拦截器能让人一眼看完授权全流程（ADR-14）。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    Permission[] value();

    /** ANY：满足任一即可（默认）；ALL：必须全部满足 */
    Logical logical() default Logical.ANY;

    enum Logical {
        ANY, ALL
    }
}
