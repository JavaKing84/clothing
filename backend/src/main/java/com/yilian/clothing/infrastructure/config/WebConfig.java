package com.yilian.clothing.infrastructure.config;

import com.yilian.clothing.infrastructure.security.PermissionInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置：跨域 + 授权拦截器 + 静态资源。
 *
 * <p>跨域用 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins}：
 * 后者在 {@code allowCredentials=true} 时不允许通配符，而门店的实际访问地址
 * 可能是 {@code http://192.168.1.7:8080}（手机连店里 WiFi），域名不固定。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    public WebConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                // 前端要读 traceId 才能在报错弹窗里展示，必须显式暴露
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/login");
    }
}
