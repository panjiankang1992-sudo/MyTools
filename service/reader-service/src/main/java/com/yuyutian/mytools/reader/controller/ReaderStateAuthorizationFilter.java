package com.yuyutian.mytools.reader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/**
 * 防止客户端绕过 Gateway 直接伪造 Reader owner 参数。
 */
@Component
public class ReaderStateAuthorizationFilter extends OncePerRequestFilter {

    private final InternalRequestAuthorizer authorizer;
    private final ObjectMapper mapper;

    /**
     * 创建 Reader 状态接口授权过滤器。
     */
    public ReaderStateAuthorizationFilter(InternalRequestAuthorizer authorizer, ObjectMapper mapper) {
        this.authorizer = authorizer;
        this.mapper = mapper;
    }

    /**
     * 对 Reader 同步状态接口执行服务令牌校验。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/reader-state/")) {
            // Servlet 过滤器不经过 MVC 异常解析，认证拒绝必须直接写入固定错误响应。
            try {
                authorizer.requireAuthorized(request.getHeader("Authorization"));
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != HttpServletResponse.SC_UNAUTHORIZED) {
                    throw exception;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(), Map.of(
                        "code", ErrorCode.INTERNAL_UNAUTHORIZED.code(),
                        "message", ErrorCode.INTERNAL_UNAUTHORIZED.message()));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
