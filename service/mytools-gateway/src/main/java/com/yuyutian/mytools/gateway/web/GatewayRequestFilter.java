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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayRequestFilter.class);
    private static final String OBSERVATION_TRACE = "gatewayObservationTrace";
    private static final String OBSERVATION_MODULE = "gatewayObservationModule";

    /** 观测只使用固定模块名，不能携带路径中的业务数据。 */
    private enum RequestModule { CONNECTIVITY, IDENTITY_SELF, READER, MEDIA, OTHER }

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
        RequestModule module = module(request.getRequestURI());
        // 诊断标识独立于客户端提供的关联头，不能把客户端标识作为日志数据。
        String observationTrace = UUID.randomUUID().toString();
        String previousTrace = MDC.get(OBSERVATION_TRACE);
        String previousModule = MDC.get(OBSERVATION_MODULE);
        long started = System.nanoTime();
        boolean forwarded = false;
        boolean completed = false;
        MDC.put(OBSERVATION_TRACE, observationTrace);
        MDC.put(OBSERVATION_MODULE, module.name());
        LOGGER.info("Gateway request phase=received module={} trace={} status=0 elapsedMs=0",
                module, observationTrace);
        try {
            Route route = route(request.getRequestURI());
            long authenticationStarted = System.nanoTime();
            boolean authenticated = false;
            boolean authenticationCompleted = false;
            try {
                if (route.enabled()) {
                    String authorization = request.getHeader("Authorization");
                    if (authorization == null || !authorization.startsWith("Bearer ")
                            || authorization.length() <= 7) {
                        unauthorized(response, correlationId);
                        return;
                    }
                    try {
                        GatewayPrincipal principal = validator.validate(authorization.substring(7));
                        authenticated = true;
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
                authenticationCompleted = true;
            } finally {
                int status = response.getStatus();
                // 未被既有认证处理器转为响应的异常需要明确标为失败，避免误记默认的 200。
                if (!authenticationCompleted && status < 400) status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                LOGGER.info("Gateway request phase=auth-complete module={} trace={} required={} authenticated={} status={} elapsedMs={}",
                        module, observationTrace, route.enabled(), authenticated, status, elapsedMillis(authenticationStarted));
            }
            forwarded = true;
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            int status = response.getStatus();
            if (!completed && status < 400) status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            // 此阶段表示过滤器后续处理返回，流式正文的传输量仍由原票据指标记录。
            LOGGER.info("Gateway request phase=upstream-complete module={} trace={} forwarded={} completed={} status={} elapsedMs={}",
                    module, observationTrace, forwarded, completed, status, elapsedMillis(started));
            restoreMdc(OBSERVATION_TRACE, previousTrace);
            restoreMdc(OBSERVATION_MODULE, previousModule);
        }
    }

    private RequestModule module(String uri) {
        if (uri.equals("/api/app/v1/connectivity/bootstrap") || uri.startsWith("/api/public/connectivity/")) {
            return RequestModule.CONNECTIVITY;
        }
        if (uri.equals("/api/app/v1/identity/me")) return RequestModule.IDENTITY_SELF;
        if (uri.equals("/api/app/v1/reader") || uri.startsWith("/api/app/v1/reader/")) return RequestModule.READER;
        if (uri.equals("/api/app/v1/media") || uri.startsWith("/api/app/v1/media/")) return RequestModule.MEDIA;
        return RequestModule.OTHER;
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private void restoreMdc(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }

    private Route route(String uri) {
        // RSS 始终认证，即使功能关闭也不能绕过用户身份校验。
        if (uri.equals("/api/app/v1/rss") || uri.startsWith("/api/app/v1/rss/")
                || uri.equals("/api/v1/rss") || uri.startsWith("/api/v1/rss/")) return new Route(true, ignored -> true);
        // 新闻始终先认证，两个公开前缀共享同一所有者隔离策略。
        if (uri.equals("/api/app/v1/news") || uri.startsWith("/api/app/v1/news/")
                || uri.equals("/api/v1/news") || uri.startsWith("/api/v1/news/")) return new Route(true, ignored -> true);
        // DSH 网页票据始终校验 MyTools 登录态，工作区授权由桥接服务检查。
        if (uri.equals("/api/app/v1/dsh/web-ticket")) return new Route(true, ignored -> true);
        // PDF 工具始终校验会话，功能开关由领域代理控制。
        if (uri.equals("/api/app/v1/pdf") || uri.startsWith("/api/app/v1/pdf/")) return new Route(true, ignored -> true);
        // 图片接口始终认证，禁用门禁由控制器统一返回。
        if (uri.startsWith("/api/app/v1/image-generation/")) return new Route(true, ignored -> true);
        // 播放票据本身就是凭据：原生播放器拿不到登录头，只能靠 URL 里的随机票据取流。
        if (uri.matches("^/api/(app/v1/)?video-generation/tickets/[a-f0-9]{32}$")) return new Route(false, ignored -> true);
        // 视频接口同样始终认证，灰度开关由控制器统一返回 503；两个前缀指向同一批处理器。
        if (uri.startsWith("/api/app/v1/video-generation/") || uri.startsWith("/api/video-generation/")) {
            return new Route(true, ignored -> true);
        }
        if (uri.matches("^/api/app/v1/media/tickets/[a-f0-9]{32}(?:/(?:mobile\\.mp4|master\\.m3u8|v[0-9]{1,4}(?:\\.m3u8|-[0-9]{5}\\.ts)))?$")) {
            return new Route(false, ignored -> true);
        }
        if (uri.matches("^/api/app/v1/drive-tickets/[a-f0-9]{32}$")) {
            return new Route(false, ignored -> true);
        }
        if (uri.matches("^/api/app/v1/audiobook-playback/tickets/[a-f0-9]{32}$")) {
            return new Route(false, ignored -> true);
        }
        if (uri.matches("^/api/app/v1/audiobook-export/tickets/[a-f0-9]{32}$")) {
            return new Route(false, ignored -> true);
        }
        if (uri.equals("/api/app/v1/connectivity/bootstrap")) {
            return new Route(true, ignored -> true);
        }
        if (uri.equals("/api/app/v1/features/reader-adaptation") || uri.startsWith("/api/app/v1/features/reader-adaptation/")) {
            return new Route(true, ignored -> true);
        }
        if (uri.startsWith("/api/app/v1/reader/")) {
            return new Route(properties.readerRouteEnabled(), properties::readerTenantAllowed);
        }
        if (uri.startsWith("/api/app/v1/drive/")) {
            return new Route(properties.driveRouteEnabled(), properties::driveTenantAllowed);
        }
        if (uri.startsWith("/api/app/v1/download-tools/")) {
            return new Route(properties.downloadRouteEnabled(), ignored -> true);
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
