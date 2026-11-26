package com.yuyutian.mytools.toolbox.service.session;

import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.mapper.session.GuestMapper;
import com.yuyutian.mytools.toolbox.model.GuestRow;
import com.yuyutian.mytools.toolbox.model.GuestSession;
import com.yuyutian.mytools.toolbox.model.SessionResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * 匿名会话服务：签发、复用与校验会话 cookie，并执行 CSRF 校验。
 * 会话有效期固定为创建起 24 小时，不因活动延长；过期或未知 cookie 一律新建会话，不复活旧会话。
 */
@Service
public class SessionService {

    /** CSRF 请求头名称，与契约的变更接口要求一致。 */
    public static final String CSRF_HEADER = "X-Toolbox-Csrf";

    private final GuestMapper guestMapper;
    private final SessionTokenService tokenService;
    private final ToolboxProperties properties;
    private final Clock clock;

    /**
     * 构造会话服务。
     *
     * @param guestMapper  会话数据访问层
     * @param tokenService token 工具
     * @param properties   门面配置
     * @param clock        统一时钟
     */
    public SessionService(GuestMapper guestMapper, SessionTokenService tokenService,
                          ToolboxProperties properties, Clock clock) {
        this.guestMapper = guestMapper;
        this.tokenService = tokenService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 签发或复用会话：有效 cookie 复用原会话，缺失、未知或过期 cookie 一律新建。
     *
     * @param request  当前请求
     * @param response 当前响应，用于写入 Set-Cookie
     * @return 会话响应，只包含到期时间与 CSRF token
     */
    public SessionResponse issueOrReuse(HttpServletRequest request, HttpServletResponse response) {
        Instant now = clock.instant();
        Optional<GuestRow> existing = findValidSession(request, now);
        if (existing.isPresent()) {
            GuestRow row = existing.get();
            writeCookie(response, readToken(request), row.expiresAt(), now);
            return new SessionResponse(row.expiresAt(), csrfTokenOf(row));
        }
        Instant expiresAt = now.plus(properties.session().ttlHours(), ChronoUnit.HOURS);
        String token = tokenService.newToken();
        byte[] tokenHash = tokenService.hash(token);
        String guestId = UUID.randomUUID().toString();
        guestMapper.insert(guestId, tokenHash, now, expiresAt);
        writeCookie(response, token, expiresAt, now);
        return new SessionResponse(expiresAt, tokenService.csrfToken(tokenHash, guestId));
    }

    /**
     * 要求存在有效会话，缺失、未知、已撤销或已过期都返回 TOOLBOX_002。
     *
     * @param request 当前请求
     * @return 已校验的会话
     */
    public GuestSession requireSession(HttpServletRequest request) {
        return findValidSession(request, clock.instant())
                .map(row -> new GuestSession(row.ownerId(), row.guestId(), row.expiresAt(), csrfTokenOf(row)))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_002));
    }

    /**
     * 要求存在有效会话且 CSRF 请求头与会话绑定值一致，否则返回 TOOLBOX_010。
     *
     * @param request 当前请求
     * @return 已校验的会话
     */
    public GuestSession requireVerified(HttpServletRequest request) {
        GuestSession session = requireSession(request);
        String provided = request.getHeader(CSRF_HEADER);
        // 先校验会话再校验 CSRF，避免把会话状态泄漏给跨站请求。
        if (!tokenService.matches(session.csrfToken(), provided)) {
            throw new BusinessException(ErrorCode.TOOLBOX_010, java.util.Map.of("reason", "csrfMismatch"));
        }
        return session;
    }

    /**
     * 返回配置的 cookie 名称，测试与文档使用。
     *
     * @return cookie 名称
     */
    public String cookieName() {
        return properties.session().cookieName();
    }

    /**
     * 查找有效会话行。
     *
     * @param request 当前请求
     * @param now     当前时间
     * @return 有效会话行或空
     */
    private Optional<GuestRow> findValidSession(HttpServletRequest request, Instant now) {
        String token = readToken(request);
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return guestMapper.findByTokenHash(tokenService.hash(token))
                // 已撤销或已过期的会话按不存在处理，由调用方决定新建。
                .filter(row -> row.revokedAt() == null && row.expiresAt() != null && row.expiresAt().isAfter(now));
    }

    /**
     * 从 cookie 读取原始 token。
     *
     * @param request 当前请求
     * @return 原始 token 或 null
     */
    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = properties.session().cookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 计算会话 CSRF token。
     *
     * @param row 会话行
     * @return CSRF token
     */
    private String csrfTokenOf(GuestRow row) {
        return tokenService.csrfToken(row.tokenHash(), row.guestId());
    }

    /**
     * 写入会话 cookie：HttpOnly、SameSite=Lax、Path=/、无 Domain，生产环境附带 Secure。
     *
     * @param response  当前响应
     * @param token     原始 token
     * @param expiresAt 会话到期时间
     * @param now       当前时间
     */
    private void writeCookie(HttpServletResponse response, String token, Instant expiresAt, Instant now) {
        Duration remaining = Duration.between(now, expiresAt);
        long maxAgeSeconds = Math.max(0, remaining.getSeconds());
        ResponseCookie cookie = ResponseCookie.from(properties.session().cookieName(), token)
                .httpOnly(true)
                .secure(properties.session().cookieSecure())
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
