package com.yuyutian.mytools.gateway.web;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.config.AppCatalogGatewayProperties;
import com.yuyutian.mytools.gateway.config.DshGatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.PrincipalValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 为启用的领域路由建立关联标识并注入可信主体。
 */
@Component
public class GatewayRequestFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "gatewayPrincipal";
    public static final String CORRELATION_ATTRIBUTE = "gatewayCorrelationId";
    private final PrincipalValidator validator;
    private final GatewayProperties properties;
    private final AppCatalogGatewayProperties appCatalogProperties;
    private final DshGatewayProperties dshProperties;

    /**
     * 创建 Gateway 请求过滤器。
     */
    @Autowired
    public GatewayRequestFilter(PrincipalValidator validator, GatewayProperties properties,
                                AppCatalogGatewayProperties appCatalogProperties,
                                DshGatewayProperties dshProperties) {
        this.validator = validator;
        this.properties = properties;
        this.appCatalogProperties = appCatalogProperties;
        this.dshProperties = dshProperties;
    }

    /**
     * 为不涉及新增路由的单元测试保留简化构造方式。
     *
     * @param validator 主体校验器
     * @param properties 既有 Gateway 配置
     */
    public GatewayRequestFilter(PrincipalValidator validator, GatewayProperties properties) {
        this(validator, properties, new AppCatalogGatewayProperties(false, "", ""),
                new DshGatewayProperties(false, "", ""));
    }

    /**
     * 仅在对应领域灰度路由启用时校验客户端令牌。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = correlation(request.getHeader("X-Correlation-Id"));
        request.setAttribute(CORRELATION_ATTRIBUTE, correlationId);
        response.setHeader("X-Correlation-Id", correlationId);
        Route route = route(request.getRequestURI());
        if (route.enabled()) {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")
                    || authorization.length() <= 7) {
                unauthorized(response, correlationId);
                return;
            }
            try {
                GatewayPrincipal principal = validator.validate(authorization.substring(7));
                if (!route.allowed(principal.userId())) {
                    routeDisabled(response, correlationId);
                    return;
                }
                request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            } catch (GatewayUnauthorizedException exception) {
                unauthorized(response, correlationId);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private Route route(String uri) {
        if (uri.matches("^/api/app/v1/media/tickets/[a-f0-9]{32}$")) {
            return new Route(false, ignored -> true);
        }
        if (uri.startsWith("/api/app/v1/reader/")) {
            return new Route(properties.readerRouteEnabled(), properties::readerTenantAllowed);
        }
        if (uri.startsWith("/api/app/v1/drive/")) {
            return new Route(properties.driveRouteEnabled(), properties::driveTenantAllowed);
        }
        if (uri.startsWith("/api/app/v1/downloads/")) {
            return new Route(properties.downloadRouteEnabled(), properties::downloadTenantAllowed);
        }
        if (uri.startsWith("/api/app/v1/media/")) {
            return new Route(properties.mediaRouteEnabled(), ignored -> true);
        }
        if (uri.equals("/api/app/v1/messages") || uri.startsWith("/api/app/v1/messages/")) {
            return new Route(properties.messagingRouteEnabled(), ignored -> true);
        }
        if (uri.equals("/api/app/v1/catalog") || uri.startsWith("/api/app/v1/catalog/")) {
            return new Route(appCatalogProperties.routeEnabled(), ignored -> true);
        }
        if (uri.equals("/api/app/v1/dsh/sessions") || uri.startsWith("/api/app/v1/dsh/sessions/")) {
            return new Route(dshProperties.routeEnabled(), ignored -> true);
        }
        if (uri.equals("/api/app/v1/identity/logout") || uri.equals("/api/app/v1/identity/me")
                || uri.equals("/api/app/v1/identity/sessions")
                || uri.startsWith("/api/app/v1/identity/sessions/")) {
            return new Route(properties.identityRouteUsable(), ignored -> true);
        }
        return new Route(false, ignored -> false);
    }

    private String correlation(String supplied) {
        if (supplied != null) {
            try {
                return UUID.fromString(supplied).toString();
            } catch (IllegalArgumentException ignored) {
                // 非法客户端关联标识不会向下游传播。
            }
        }
        return UUID.randomUUID().toString();
    }

    private void unauthorized(HttpServletResponse response, String correlationId) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Correlation-Id", correlationId);
        response.getWriter().write("{\"code\":\"GATEWAY_001\",\"message\":\"Gateway authentication failed\"}");
    }

    private void routeDisabled(HttpServletResponse response, String correlationId) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Correlation-Id", correlationId);
        response.getWriter().write("{\"code\":\"GATEWAY_002\",\"message\":\"Gateway route is not enabled\"}");
    }

    private record Route(boolean enabled, java.util.function.LongPredicate tenantPolicy) {
        private boolean allowed(long userId) {
            return tenantPolicy.test(userId);
        }
    }
}
