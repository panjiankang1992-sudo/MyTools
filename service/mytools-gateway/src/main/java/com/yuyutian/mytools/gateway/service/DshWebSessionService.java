package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.DshWebProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** 使用一次性票据连接 MyTools 登录态与 DSH 服务端会话，凭据只存于内存。 */
@Service
public class DshWebSessionService {
    private static final int CAPACITY = 2048;
    private static final long TICKET_MILLIS = 30_000;
    private static final long SESSION_MILLIS = 30 * 60_000;
    private final DshWebProperties properties;
    private final PrincipalValidator validator;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> tickets = new HashMap<>();
    private final Map<String, Grant> sessions = new HashMap<>();
    // 局域网 Cookie 的会话与 HTTPS 会话隔离，不能通过改名跨入口使用。
    private final Map<String, Grant> lanSessions = new HashMap<>();

    /** 创建认证桥接服务。 */
    @org.springframework.beans.factory.annotation.Autowired
    public DshWebSessionService(DshWebProperties properties, PrincipalValidator validator) {
        this(properties, validator, Clock.systemUTC());
    }
    DshWebSessionService(DshWebProperties properties, PrincipalValidator validator, Clock clock) {
        this.properties = properties; this.validator = validator; this.clock = clock;
    }

    /** 为已验证且具备工作区访问权限的账号签发一次性票据。 */
    public synchronized String issue(GatewayPrincipal principal, String bearer) {
        requireAllowed(principal);
        cleanup();
        // 同一登录会话只保留最新待使用票据，防止重复点击耗尽容量。
        tickets.values().removeIf(value -> sameLogin(value, principal, bearer));
        if (tickets.size() >= CAPACITY) throw new GatewayDownstreamException();
        String ticket = opaque();
        tickets.put(ticket, new Grant(principal, bearer, clock.millis() + TICKET_MILLIS));
        return ticket;
    }

    /** 消费票据并再次验证登录状态，拒绝重放、过期及已退出账号。 */
    public synchronized String redeem(String ticket) {
        return redeemFor(ticket, sessions);
    }

    /**
     * 由持有代理密钥的局域网入口消费一次性票据。
     *
     * @param proxySecret 内部代理密钥。
     * @param ticket 一次性票据。
     * @return 仅供局域网入口使用的会话标识。
     */
    public synchronized String redeemLan(String proxySecret, String ticket) {
        // 在删除票据前先验证内部代理，避免未授权请求消耗有效票据。
        requireProxy(proxySecret);
        return redeemFor(ticket, lanSessions);
    }

    private String redeemFor(String ticket, Map<String, Grant> targetSessions) {
        cleanup();
        Grant grant = tickets.remove(ticket);
        validate(grant);
        // 一个 MyTools 登录会话只保留最新网页会话，重连时撤销旧 Cookie。
        sessions.values().removeIf(value -> sameLogin(value, grant.principal(), grant.bearer()));
        lanSessions.values().removeIf(value -> sameLogin(value, grant.principal(), grant.bearer()));
        if (sessions.size() + lanSessions.size() >= CAPACITY) throw new GatewayDownstreamException();
        String session = opaque();
        targetSessions.put(session, new Grant(grant.principal(), grant.bearer(), clock.millis() + SESSION_MILLIS));
        return session;
    }

    /** 验证内部代理、浏览器会话与当前登录态后返回服务端上游凭据。 */
    public synchronized String authorize(String proxySecret, String session) {
        return authorizeFor(proxySecret, session, sessions);
    }

    /**
     * 验证内部代理及局域网专属浏览器会话。
     *
     * @param proxySecret 内部代理密钥。
     * @param session 局域网会话标识。
     * @return 只交给内部代理的上游 Cookie。
     */
    public synchronized String authorizeLan(String proxySecret, String session) {
        return authorizeFor(proxySecret, session, lanSessions);
    }

    private String authorizeFor(String proxySecret, String session, Map<String, Grant> sourceSessions) {
        requireProxy(proxySecret);
        cleanup();
        Grant grant = sourceSessions.get(session);
        try { validate(grant); }
        catch (GatewayUnauthorizedException | DshWebForbiddenException exception) {
            sourceSessions.remove(session);
            throw exception;
        }
        try {
            Path path = Path.of(properties.getCookieFile());
            // 上游 Cookie 不返回 APP，只通过内部鉴权响应交给 Nginx。
            if (Files.size(path) > 4096) throw new GatewayDownstreamException();
            String cookie = Files.readString(path).trim();
            if (!cookie.matches("dsh-auth-[A-Za-z0-9_-]{43}=v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) {
                throw new GatewayDownstreamException();
            }
            return cookie;
        } catch (java.io.IOException exception) { throw new GatewayDownstreamException(); }
    }

    private void requireProxy(String proxySecret) {
        byte[] configured = properties.getProxySecret().getBytes(StandardCharsets.UTF_8);
        if (configured.length < 32 || proxySecret == null || !MessageDigest.isEqual(configured,
                proxySecret.getBytes(StandardCharsets.UTF_8))) throw new GatewayUnauthorizedException();
    }

    private void validate(Grant grant) {
        if (grant == null || grant.expiresAt() <= clock.millis()) throw new GatewayUnauthorizedException();
        GatewayPrincipal current = validator.validate(grant.bearer());
        requireAllowed(current);
        if (current.userId() != grant.principal().userId()
                || !java.util.Objects.equals(current.sessionId(), grant.principal().sessionId())) throw new GatewayUnauthorizedException();
    }
    private void requireAllowed(GatewayPrincipal principal) {
        if (!properties.isEnabled()) throw new GatewayRouteDisabledException();
        if (principal == null) throw new GatewayUnauthorizedException();
        // 没有工作区权限返回 403，避免 APP 把它当成登录过期而退出账号。
        if (!properties.getAllowedUsernames().contains(principal.username())) throw new DshWebForbiddenException();
    }
    private boolean sameLogin(Grant value, GatewayPrincipal principal, String bearer) {
        // 旧认证契约没有 sessionId，以已验证的原始访问令牌区分登录，仍逐次校验撤销状态。
        return value.principal().userId() == principal.userId()
                && (principal.sessionId() == null ? value.bearer().equals(bearer)
                : principal.sessionId().equals(value.principal().sessionId()));
    }
    private void cleanup() {
        long now = clock.millis();
        tickets.values().removeIf(value -> value.expiresAt() <= now);
        sessions.values().removeIf(value -> value.expiresAt() <= now);
        lanSessions.values().removeIf(value -> value.expiresAt() <= now);
    }
    private String opaque() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private record Grant(GatewayPrincipal principal, String bearer, long expiresAt) { }
}
