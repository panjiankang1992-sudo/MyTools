package com.yuyutian.mytools.toolbox.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.config.AdminProperties;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CORS 与来源过滤器：只允许精确配置的站点来源携带凭据访问，跨站变更请求一律拒绝。
 * 不使用通配符，未配置来源时对变更请求失败关闭。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorsAndOriginFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** 公开接口允许的方法。 */
    private static final List<String> PUBLIC_METHODS = List.of("GET", "POST", "OPTIONS");
    /** 公开接口允许的请求头。 */
    private static final List<String> PUBLIC_HEADERS = List.of("Content-Type", "X-Toolbox-Csrf", "X-Request-Id");
    /** 管理接口额外需要 PUT 与并发／幂等前提头；不改变公开白名单。 */
    private static final List<String> ADMIN_METHODS = List.of("GET", "POST", "PUT", "OPTIONS");
    /** 管理接口前缀。 */
    private static final String ADMIN_PREFIX = "/admin/v1";

    private final ToolboxProperties properties;
    private final AdminProperties adminProperties;
    private final ObjectMapper objectMapper;

    /**
     * 构造过滤器。
     *
     * @param properties      门面配置
     * @param adminProperties 管理门面配置，用于取独立的管理 CSRF 头名
     * @param objectMapper    JSON 序列化器，用于直接返回错误信封
     */
    public CorsAndOriginFilter(ToolboxProperties properties, AdminProperties adminProperties,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.adminProperties = adminProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean mutating = MUTATING_METHODS.contains(request.getMethod().toUpperCase());
        if (origin != null && !origin.isBlank()) {
            response.addHeader("Vary", "Origin");
            // 只有精确等于配置来源才回带 CORS 头，绝不使用通配符。
            if (matchesConfiguredOrigin(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
                // 管理接口与管理无关的接口使用各自的方法／请求头白名单；
                // 管理额外允许 PUT、If-Match、Idempotency-Key 与独立 CSRF 头，但不放宽来源白名单。
                boolean admin = request.getServletPath().startsWith(ADMIN_PREFIX);
                List<String> methods = admin ? ADMIN_METHODS : PUBLIC_METHODS;
                List<String> headers = admin
                        ? List.of("Content-Type", adminProperties.csrfHeaderName(), "If-Match", "Idempotency-Key", "X-Request-Id")
                        : PUBLIC_HEADERS;
                response.setHeader("Access-Control-Allow-Methods", String.join(", ", methods));
                response.setHeader("Access-Control-Allow-Headers", String.join(", ", headers));
                response.setHeader("Access-Control-Max-Age", "600");
            } else if (mutating) {
                writeForbidden(response);
                return;
            }
        }
        // 浏览器 Fetch-Metadata：明确标记跨站的变更请求直接拒绝。
        if (mutating && "cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            writeForbidden(response);
            return;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 判断请求来源是否与配置来源精确相等且配置有效。
     *
     * @param origin 请求 Origin 头
     * @return 是否允许携带凭据访问
     */
    private boolean matchesConfiguredOrigin(String origin) {
        String listed = properties.publicOrigins();
        if (listed != null && !listed.isBlank()) {
            // 逗号分隔清单：任一精确命中即放行，绝不接受通配符。
            for (String candidate : listed.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty() && !"*".equals(trimmed) && trimmed.equals(origin)) {
                    return true;
                }
            }
            return false;
        }
        String configured = properties.publicOrigin();
        return configured != null && !configured.isBlank() && !"*".equals(configured) && configured.equals(origin);
    }

    /**
     * 返回统一的来源校验失败信封。
     *
     * @param response HTTP 响应
     * @throws IOException 写入响应失败
     */
    private void writeForbidden(HttpServletResponse response) throws IOException {
        ApiErrorResponse body = ApiErrorResponse.of(ErrorCode.TOOLBOX_010);
        response.setStatus(ErrorCode.TOOLBOX_010.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
