package com.yilian.clothing.infrastructure.security;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 授权拦截器：读 {@link RequirePermission}，比对当前操作人的权限码。
 *
 * <p>为什么授权放在拦截器而不是过滤器：注解在 Controller 方法上，
 * 只有 Spring MVC 解析出 HandlerMethod 之后才拿得到；过滤器阶段还只有一个 URL 字符串。
 * 用 URL 前缀做权限判断是另一种常见做法，但它把"权限"和"路径"耦合死，
 * 一改路由就悄悄改错权限。</p>
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequirePermission annotation = method.getMethodAnnotation(RequirePermission.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (annotation == null) {
            return true;
        }
        Operator operator = AuthContext.current();
        if (operator == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        Permission[] required = annotation.value();
        boolean pass = annotation.logical() == RequirePermission.Logical.ALL
                ? Arrays.stream(required).allMatch(operator::can)
                : Arrays.stream(required).anyMatch(operator::can);
        if (!pass) {
            // 403 而不是 401：401 会让前端跳登录页，而这里用户是登录着的，只是没这个权限
            throw BizException.of(ResultCode.FORBIDDEN,
                    "需要权限：" + Arrays.stream(required).map(Permission::code).toList());
        }
        return true;
    }
}
