package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 防止客户端绕过 Gateway 直接伪造 Reader owner 参数。
 */
@Component
public class ReaderStateAuthorizationFilter extends OncePerRequestFilter {

    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建 Reader 状态接口授权过滤器。
     */
    public ReaderStateAuthorizationFilter(InternalRequestAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /**
     * 对 Reader 同步状态接口执行服务令牌校验。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/reader-state/")) {
            authorizer.requireAuthorized(request.getHeader("Authorization"));
        }
        filterChain.doFilter(request, response);
    }
}
