package com.yilian.clothing.infrastructure.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪：每个请求一个 traceId，写进 MDC（日志）与响应头（前端）。
 *
 * <p>为什么用 MDC 而不是把 traceId 一路当参数传：
 * 参数会污染所有方法签名，而 MDC 是 ThreadLocal，随线程走。虚拟线程下 MDC 仍然可用
 * （Spring Boot 3.2+ 对虚拟线程做了 MDC 适配），这正是我们敢开
 * {@code spring.threads.virtual.enabled=true} 的前提之一。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put("traceId", traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清理：Tomcat 会复用线程，不清理会把上一个请求的 traceId 带给下一个用户
            MDC.remove("traceId");
        }
    }
}
