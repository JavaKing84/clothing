package com.yilian.clothing.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.Role;
import com.yilian.clothing.domain.repository.SupportRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 认证过滤器：Bearer Token → {@link AuthContext}。
 *
 * <p>三个容易写错的点，这里都显式处理：
 * <ol>
 *   <li><b>放行白名单</b>必须包含 OPTIONS 预检，否则浏览器跨域请求会在预检就被 401，
 *       前端只看到"CORS 错误"，根本看不到真正原因。</li>
 *   <li><b>权限从库里取</b>而不是从 token 里取：店主把店员降级后，下一个请求立刻生效。</li>
 *   <li><b>finally 清理 ThreadLocal</b>，防止线程复用导致越权。</li>
 * </ol></p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String[] PUBLIC_PREFIXES = {
            "/api/v1/auth/login", "/actuator", "/doc", "/swagger-ui", "/v3/api-docs",
            "/openapi.json", "/error", "/assets", "/favicon"
    };

    private final JwtService jwtService;
    private final SupportRepository supportRepository;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, SupportRepository supportRepository, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.supportRepository = supportRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        // 单端口演示时前端静态资源（/、/index.html、/login 等 hash 路由）也要放行
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, ResultCode.UNAUTHORIZED);
            return;
        }
        try {
            var claims = jwtService.parse(header.substring(7));
            long userId = Long.parseLong(claims.getSubject());
            Role role = Role.of(claims.get("role", String.class));
            Set<String> perms = supportRepository.findPermissions(role.name());
            Operator operator = new Operator(userId, claims.get("phone", String.class),
                    claims.get("nickname", String.class), role,
                    claims.get("shopId", Number.class).longValue(), perms);
            AuthContext.set(operator);
            chain.doFilter(request, response);
        } catch (Exception e) {
            // 过期、伪造、格式错误统一 401：不告诉攻击者是"签名不对"还是"过期"，
            // 避免为爆破提供信息
            writeUnauthorized(response, ResultCode.UNAUTHORIZED);
        } finally {
            AuthContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response, ResultCode rc) throws IOException {
        response.setStatus(rc.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", rc.code());
        body.put("msg", rc.msg());
        body.put("data", null);
        body.put("traceId", "");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
