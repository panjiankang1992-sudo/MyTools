package com.yuyutian.mytools.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC配置类。
 * 配置拦截器等Web相关组件。
 *
 * @author mytools
 * @since 2026-05-11
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiLogInterceptor apiLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiLogInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/logs/**"  // 排除日志查询接口本身
                );
    }
}
