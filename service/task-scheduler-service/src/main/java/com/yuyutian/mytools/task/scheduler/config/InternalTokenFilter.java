package com.yuyutian.mytools.task.scheduler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * 校验 Executor 内部接口与业务任务接口的服务令牌。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Task-Internal-Token";
    public static final String BUSINESS_TOKEN_HEADER = "X-Task-Business-Token";
    public static final String SERVICE_ID_HEADER = "X-Task-Service-Id";
    public static final String AUTHENTICATED_SERVICE_ATTRIBUTE =
            "com.yuyutian.mytools.task.authenticatedServiceId";

    private final TaskSecurityProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建内部令牌过滤器。
     *
     * @param properties 安全配置
     * @param objectMapper JSON 映射器
     */
    public InternalTokenFilter(TaskSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 对受保护的服务接口执行恒定时间令牌校验。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 处理失败
     * @throws IOException IO 处理失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean businessPath = isBusinessPath(request.getRequestURI());
        String serviceId = request.getHeader(SERVICE_ID_HEADER);
        String configuredToken = configuredToken(businessPath, serviceId);
        String requestToken = request.getHeader(businessPath ? BUSINESS_TOKEN_HEADER : INTERNAL_TOKEN_HEADER);
        if (!properties.required() || tokensEqual(configuredToken, requestToken)) {
            if (properties.required()) {
                // 将已经由独立凭据绑定的服务身份传给后续审计链路。
                request.setAttribute(AUTHENTICATED_SERVICE_ATTRIBUTE,
                        serviceId == null || serviceId.isBlank() ? legacyServiceId(businessPath) : serviceId);
            }
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
                ErrorCode.UNAUTHORIZED.name(), "Internal authentication failed", Instant.now()));
    }

    /**
     * 仅过滤 Executor 内部入口与业务任务入口。
     *
     * @param request HTTP 请求
     * @return 是否跳过过滤
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isProtectedPath(request.getRequestURI());
    }

    static boolean isProtectedPath(String path) {
        if (isBusinessPath(path)) {
            return true;
        }
        if (path.startsWith("/internal/v1/outbox/")) {
            return true;
        }
        if ("/api/v1/execution-topology/nodes/register".equals(path)) {
            return true;
        }
        if (path.matches("/api/v1/execution-topology/nodes/[^/]+/heartbeat")) {
            return true;
        }
        if (path.matches("/api/v1/execution-topology/nodes/[^/]+/status")) {
            return true;
        }
        if ("/internal/v1/executions/claim".equals(path)) {
            return true;
        }
        return path.matches("/internal/v1/executions/[^/]+/(heartbeat|complete|steps/report)");
    }

    private static boolean isBusinessPath(String path) {
        return "/api/v1/task-instances".equals(path) || path.startsWith("/api/v1/task-instances/");
    }

    private String businessToken() {
        return properties.businessToken() == null || properties.businessToken().isBlank()
                ? properties.internalToken() : properties.businessToken();
    }

    private String configuredToken(boolean businessPath, String serviceId) {
        var clients = businessPath ? properties.businessClients() : properties.internalClients();
        if (clients.isEmpty()) {
            return businessPath ? businessToken() : properties.internalToken();
        }
        if (serviceId == null || !serviceId.matches("^[a-z][a-z0-9-]{1,63}$")) {
            return null;
        }
        return clients.get(serviceId);
    }

    private String legacyServiceId(boolean businessPath) {
        return businessPath ? "legacy-business-client" : "legacy-internal-client";
    }

    private boolean tokensEqual(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
