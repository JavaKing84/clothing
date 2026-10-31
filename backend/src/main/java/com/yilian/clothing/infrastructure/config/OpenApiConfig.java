package com.yilian.clothing.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档（DoD 第 3 条：核心接口必须有示例）。
 *
 * <p>把 Bearer 鉴权方案注册进去，Swagger UI 右上角就能填 token 直接试接口，
 * 不用再拿 curl 手拼 Authorization 头。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI clothingOpenApi() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("衣恋时尚女装 · 扫码出入库系统 API")
                        .version("1.0.0")
                        .description("""
                                业务真源：《衣恋-76题需求答卷》。
                                响应统一为 {code,msg,data,traceId}；1001=库存不足(409)，2003=条码无法识别(404)。
                                并发不覆盖：行锁 + 原子增量 + 幂等令牌（见 Decision_Log ADR-04）。
                                """)
                        .contact(new Contact().name("衣恋项目组")))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
