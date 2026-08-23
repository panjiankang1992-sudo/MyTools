package com.yuyutian.mytools.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.yuyutian.mytools.auth.filter.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.common.MessageHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security配置类。
 * 配置JWT认证过滤器和URL授权规则。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    /**
     * 配置安全过滤器链，并只公开明确列出的认证与注册端点。
     *
     * @param http HttpSecurity配置对象
     * @return 安全过滤器链
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/register/code",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/public/register",
                                "/api/public/register/code",
                                "/api/public/feedback").permitAll()
                        .requestMatchers("/api/route/**").permitAll()
                        .requestMatchers("/api/tokens/validate").permitAll()
                        .requestMatchers("/api/market/files/**").permitAll()
                        .requestMatchers("/market-files/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/connectivity/challenge").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/app/v1/media/tickets/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/app/v1/local-media/tickets/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/app/v1/drive-tickets/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/v1/migration/drive-accounts").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                writeSecurityError(response, ErrorCode.AUTH_002))
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            ErrorCode errorCode = Boolean.TRUE.equals(request.getAttribute("jwtAuthenticationFailed"))
                                    ? ErrorCode.AUTH_002
                                    : ErrorCode.AUTH_003;
                            writeSecurityError(response, errorCode);
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeSecurityError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Result.error(
                errorCode.getCode(),
                MessageHelper.getMessage(errorCode.getMessageKey()),
                null
        );
        objectMapper.writeValue(response.getWriter(), result);
    }

    /**
     * 配置密码编码器。
     *
     * @return BCrypt密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
