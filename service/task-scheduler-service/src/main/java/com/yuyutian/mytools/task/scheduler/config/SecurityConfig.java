package com.yuyutian.mytools.task.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * 调度服务安全过滤链配置。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /**
     * 配置无会话的内部接口鉴权过滤链。
     *
     * @param http Spring Security HTTP 配置
     * @param internalTokenFilter 内部令牌过滤器
     * @return 安全过滤链
     * @throws Exception 配置安全过滤链失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   InternalTokenFilter internalTokenFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(internalTokenFilter, AnonymousAuthenticationFilter.class)
                .build();
    }
}
