package com.yuyutian.mytools.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

/**
 * Web配置类。
 * 配置CORS跨域请求支持和HTTP客户端。
 *
 * @author mytools
 * @since 2026-05-03
 */
@Configuration
public class WebConfig {

    /**
     * 配置CORS过滤器。
     *
     * @return CorsFilter
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许的来源
        config.addAllowedOriginPattern("*");

        // 允许携带认证信息
        config.setAllowCredentials(true);

        // 允许的HTTP方法
        config.addAllowedMethod("*");

        // 允许的请求头
        config.addAllowedHeader("*");

        // 暴露的响应头
        config.addExposedHeader("Authorization");

        // 预检请求缓存时间
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }

    /**
     * 配置 RestTemplate。
     * 用于调用外部HTTP服务（如打标签服务）。
     * 使用 HttpComponentsClientHttpRequestFactory 以支持 HTTP/1.0
     *
     * @return RestTemplate实例
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);  // 10秒连接超时
        factory.setReadTimeout(60000);      // 60秒读取超时
        return new RestTemplate(factory);
    }
}
