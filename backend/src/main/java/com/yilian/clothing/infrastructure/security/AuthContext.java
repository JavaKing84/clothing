package com.yilian.clothing.infrastructure.security;

import com.yilian.clothing.domain.model.Operator;

/**
 * 当前请求的操作人（ThreadLocal）。
 *
 * <p>为什么用 ThreadLocal 而不是把 Operator 当参数层层传递：
 * 一是污染所有方法签名，二是容易漏传（漏传就会用错人写流水）。
 * ThreadLocal 的代价是**必须在 finally 里 clear**，否则线程池复用会把
 * 上一个请求的登录态带给下一个用户——这是最危险的一类越权 bug。
 * {@link JwtAuthFilter} 的 finally 块就是干这个的。</p>
 *
 * <p>虚拟线程下 ThreadLocal 依然正确（每个虚拟线程独立栈），
 * 且不会像平台线程池那样长期复用，泄漏风险更低。</p>
 */
public final class AuthContext {

    private static final ThreadLocal<Operator> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(Operator operator) {
        HOLDER.set(operator);
    }

    public static Operator current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
