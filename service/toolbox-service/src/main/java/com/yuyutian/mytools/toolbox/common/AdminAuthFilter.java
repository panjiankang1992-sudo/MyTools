package com.yuyutian.mytools.toolbox.common;

import com.yuyutian.mytools.toolbox.config.AdminProperties;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminAuthService;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理接口的逐请求鉴权。
 * 默认要求管理员；精确例外只有四个：查询会话、发放登录 nonce、登录、退出。
 * 禁止按 /auth/** 通配放行，避免将来新增的认证子路径被意外暴露。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AdminAuthFilter extends OncePerRequestFilter {

    /** 管理接口前缀。 */
    private static final String ADMIN_PREFIX = "/admin/v1";
    /** 无需管理员会话即可访问的精确路径与方法组合。 */
    private static final Set<String> PUBLIC_EXACT = Set.of(
            "GET /admin/v1/session",
            "GET /admin/v1/auth/csrf",
            "POST /admin/v1/auth/login",
            "POST /admin/v1/auth/logout");

    private final AdminAuthService authService;
    private final AdminSessionService sessionService;
    private final AdminProperties properties;

    /**
     * 创建管理鉴权过滤器。
     *
     * @param authService    管理员鉴权服务
     * @param sessionService 管理会话服务
     * @param properties     管理员门面配置
     */
    public AdminAuthFilter(AdminAuthService authService, AdminSessionService sessionService,
                           AdminProperties properties) {
        // 启动即校验 Cookie 配置：前缀与 Secure 不一致时会以隐晦的 403 形式暴露，难以排查。
        properties.validateCookieConfiguration();
        this.authService = authService;
        this.sessionService = sessionService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (!path.startsWith(ADMIN_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        // 管理接口一律禁止缓存，包括失败响应。
        response.setHeader("Cache-Control", "no-store");
        // 四个精确例外先放行：其中"查询会话"即使管理功能关闭也必须能匿名回答未登录，
        // 否则前端无法区分"功能未启用"和"当前未登录"。
        if (PUBLIC_EXACT.contains(request.getMethod() + " " + path)) {
            chain.doFilter(request, response);
            return;
        }
        if (!properties.enabled()) {
            FilterResponses.writeError(response, ErrorCode.TOOLBOX_ADMIN_009, "adminDisabled");
            return;
        }
        String sessionId = readCookie(request, properties.sessionCookieName());
        Optional<AdminSession> session;
        try {
            session = authService.authenticate(sessionId);
        } catch (RuntimeException unavailable) {
            // 依赖不可用必须与"未登录"区分：锁定写入但不谎称已退出。
            FilterResponses.writeError(response, ErrorCode.TOOLBOX_ADMIN_009, "identityUnavailable");
            return;
        }
        if (session.isEmpty()) {
            FilterResponses.writeError(response, ErrorCode.TOOLBOX_ADMIN_001, "sessionRequired");
            return;
        }
        // A08：**按需提前刷新**。
        //
        // 放在这里而不是"等上游返回 401 再刷"：那条路只能救回**已经失败**的请求，
        // 而且在并发下会重复打上游。这里的判据是**剩余时间**，
        // 所以刷新发生在令牌还可用的时候。
        //
        // 三点安全性来自组件本身（第 66 轮已逐条测试）：
        // - 需要刷时才刷；不需要、抢不到、刷新失败，**都返回原会话**——
        //   **本请求照常继续**，不会因为刷新问题让人掉线；
        // - 抢不到刷新权是**安全的空操作**（说明别的请求正在刷，复用它的结果）；
        // - 刷新失败**不改动会话**（访问令牌可能仍然可用）。
        // **只调用一次**：写两遍（条件里一次、赋值里一次）等于把刷新跑两次，
        // 那正好破坏了它内部的单飞语义——我第一版就是这么写的，编译能过，但语义是错的。
        Optional<AdminSession> refreshed = authService.refreshIfNeeded(sessionId);
        if (refreshed.isPresent()) {
            session = refreshed;
        }
        // 写请求必须携带与会话绑定的 CSRF 令牌；SameSite 与来源校验不能替代它。
        if (!"GET".equalsIgnoreCase(request.getMethod())
                && !sessionService.csrfMatches(session.get(), request.getHeader(properties.csrfHeaderName()))) {
            FilterResponses.writeError(response, ErrorCode.TOOLBOX_ADMIN_003, "csrfMismatch");
            return;
        }
        request.setAttribute(AdminSession.class.getName(), session.get());
        chain.doFilter(request, response);
    }

    /** 读取指定名称的 Cookie。 */
    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
