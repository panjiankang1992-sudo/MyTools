package com.yuyutian.mytools.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger配置类。
 *
 * @author mytools
 * @since 2026-05-03
 */
@Configuration
public class OpenApiConfig {

    /**
     * 配置OpenAPI信息。
     *
     * @return OpenAPI配置
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MyTools API")
                        .version("1.0.0")
                        .description("微信朋友圈任务管理系统后端API文档")
                        .contact(new Contact()
                                .name("MyTools Team")
                                .email("admin@mytools.com")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("输入JWT令牌")));
    }
}
