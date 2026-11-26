package com.yuyutian.mytools.toolbox.controller.admin.auth;

import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.common.AdminLoginRateLimiter;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.AdminProperties;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityException;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminAuthService;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminLoginChallengeStore;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端登录门面。
 * 全部响应 no-store；密码只在本次请求中使用，不落库、不写日志、不回传浏览器。
 */
@RestController
@RequestMapping("/admin/v1")
public final class AdminAuthController {

    private final AdminAuthService authService;
    private final AdminLoginChallengeStore challengeStore;
    private final AdminProperties properties;

    private final AdminLoginRateLimiter rateLimiter;

    /**
     * 创建管理登录控制器。
     *
     * @param authService    管理员鉴权服务
     * @param challengeStore 预认证挑战存储
     * @param properties     管理员门面配置
     */
    public AdminAuthController(AdminAuthService authService, AdminLoginChallengeStore challengeStore,
                               AdminLoginRateLimiter rateLimiter, AdminProperties properties) {
        this.authService = authService;
        this.challengeStore = challengeStore;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * 发放登录表单所需的一次性 CSRF nonce，并绑定预认证 Cookie。
     *
     * @param response HTTP 响应
     * @return nonce
     */
    @GetMapping("/auth/csrf")
    public ResponseEntity<ApiResponse<Map<String, Object>>> csrf(HttpServletResponse response) {
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_009, Map.of("reason", "adminDisabled"));
        }
        AdminLoginChallengeStore.IssuedChallenge challenge = challengeStore.issue();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(properties.loginCookieName(),
                challenge.cookieValue(), 600).toString());
        return noStore(ApiResponse.ok(Map.of("csrfToken", challenge.nonce())));
    }

    /**
     * 使用 MyTools 管理员账号登录工具箱。
     *
     * @param request  HTTP 请求，用于读取预认证 Cookie
     * @param nonce    请求头携带的 CSRF nonce
     * @param body     登录请求体
     * @param response HTTP 响应
     * @return 安全用户摘要与新的 CSRF 令牌
     */
    /**
     * 取限流用的来源标识。
     *
     * 优先用 Nginx 写入的 `X-Forwarded-For`（生产上应用只在 Nginx 之后被访问）；
     * 直接访问时回落到 `getRemoteAddr()`。
     *
     * **信任假设**：该头在部署中由 Nginx 覆盖写入。若应用被直接暴露且未清洗此头，
     * 调用方可伪造它来绕过限流——**这一点是部署约束，不是代码能兜住的**。
     *
     * @param request HTTP 请求
     * @return 来源标识
     */
    private static String clientSource(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            HttpServletRequest request,
            @RequestHeader(name = "X-Toolbox-Admin-Csrf", required = false) String nonce,
            @RequestBody LoginBody body,
            HttpServletResponse response) {
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_009, Map.of("reason", "adminDisabled"));
        }
        // 登录限流：**只统计失败**，成功登录会清零，因此不影响正常使用。
        String source = clientSource(request);
        long retryAfter = rateLimiter.retryAfterSeconds(source);
        if (retryAfter > 0) {
            // 先把 Retry-After 写进响应，再交给全局异常处理器渲染错误信封——
            // BusinessException 本身带不了响应头。
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_008,
                    Map.of("retryAfterSeconds", retryAfter));
        }
        // 登录必须携带一次性 nonce，且与预认证 Cookie 绑定；缺少或重放一律拒绝。
        if (!challengeStore.consume(readCookie(request, properties.loginCookieName()), nonce)) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_003, Map.of("field", "csrfToken"));
        }
        AdminSessionService.IssuedSession issued;
        try {
            issued = authService.login(body.account(), body.password());
        } catch (AdminIdentityException exception) {
            // 只有失败才计入配额：否则正常用户会在多次成功登录后被挡住。
            rateLimiter.recordFailure(source);
            throw mapFailure(exception.failure());
        }
        // 成功即清零，避免"昨天输错过一次"在今天仍占配额。
        rateLimiter.clear(source);
        // 成功：删除预认证 Cookie，下发正式会话 Cookie。
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(properties.loginCookieName(), "", 0).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(properties.sessionCookieName(), issued.sessionId(),
                (int) Duration.ofHours(properties.absoluteHours()).toSeconds()).toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authenticated", true);
        data.put("username", issued.session().username());
        data.put("userId", issued.session().mytoolsUserId());
        data.put("csrfToken", issued.csrfToken());
        return noStore(ApiResponse.ok(data));
    }

    /**
     * 查询当前会话状态；无 Cookie 时匿名返回未登录，不创建任何会话。
     *
     * @param sessionId 管理会话 Cookie，可为空
     * @return 认证状态
     */
    @GetMapping("/session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> session(
            @CookieValue(name = "${toolbox.admin.session-cookie-name:__Host-toolbox_admin}", required = false) String sessionId) {
        Optional<AdminSession> session = properties.enabled() && sessionId != null
                ? authService.peek(sessionId)
                : Optional.empty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authenticated", session.isPresent());
        data.put("adminEnabled", properties.enabled());
        session.ifPresent(value -> {
            data.put("username", value.username());
            data.put("userId", value.mytoolsUserId());
            // F01：把**同一个** CSRF 令牌还给浏览器，使刷新／新标签页后仍可写入。
            // 这里不轮换令牌——轮换会让已持有旧令牌的其它标签页立刻失效。
            // 该响应对跨站不可读：管理路径受来源校验保护，且响应带 no-store。
            authService.csrfTokenFor(value).ifPresent(token -> data.put("csrfToken", token));
        });
        return noStore(ApiResponse.ok(data));
    }

    /**
     * 退出登录；即使上游不可用也完成本地清理。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @return 退出结果
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logout(
            HttpServletRequest request,
            @RequestHeader(name = "X-Toolbox-Admin-Csrf", required = false) String csrf,
            HttpServletResponse response) {
        String sessionId = readCookie(request, properties.sessionCookieName());
        // F13：退出**必须校验本地 CSRF**。
        //
        // 此前同源的、不带 CSRF 头的请求也能真正撤销会话——那是一条**强制注销**路径：
        // 任何能让浏览器发出同源 POST 的地方（例如一个被注入的页面片段、
        // 或用户误触的链接）都能把管理员踢下线，而且不需要知道任何令牌。
        //
        // 校验是**纯本地**的（比对会话里存的哈希），因此"上游不可用也能安全退出"不受影响。
        if (sessionId != null) {
            java.util.Optional<AdminSession> current = authService.peek(sessionId);
            if (current.isPresent() && !authService.csrfMatches(current.get(), csrf)) {
                throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_003, Map.of("field", "csrfToken"));
            }
        }
        authService.logout(sessionId);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(properties.sessionCookieName(), "", 0).toString());
        return noStore(ApiResponse.ok(Map.of("authenticated", false)));
    }

    /** 构造与配置一致的 Cookie；PreAuth Cookie 使用固定的 __Host- 名称。 */
    private ResponseCookie cookie(String name, String value, int maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    /** 从请求中读取指定 Cookie。 */
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

    /** 把上游失败分类映射为稳定的管理错误码。 */
    private static BusinessException mapFailure(AdminIdentityFailure failure) {
        return switch (failure) {
            case REJECTED -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_001, Map.of());
            case NOT_ADMIN -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_002, Map.of());
            case UNAVAILABLE -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_009, Map.of());
        };
    }

    /** 给响应加上禁止缓存的头。 */
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    /**
     * 登录请求体。
     *
     * @param account  用户名或邮箱
     * @param password 密码
     */
    public record LoginBody(String account, String password) {
    }
}
