package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.DshWebSessionService;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** MyTools 登录态到 DSH 网页会话的认证桥接入口。 */
@RestController
public class DshWebController {
    private final DshWebSessionService service;
    /** 创建桥接控制器。 */
    public DshWebController(DshWebSessionService service) { this.service = service; }

    /** 签发由可信 HTTPS 或经过验证的局域网代理消费的一次性票据。 */
    @PostMapping("/api/app/v1/dsh/web-ticket")
    public Map<String, Object> ticket(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        GatewayPrincipal principal = (GatewayPrincipal) request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        String value = service.issue(principal, request.getHeader("Authorization").substring(7));
        // 统一信封包含嵌套对象，不能让 JSON 转换器把所有值固定序列化为字符串。
        return Map.of("url", "https://dsh-app.yuyutian.top/_mytools/connect?ticket=" + value);
    }

    /** 票据消费后写入仅 HTTPS 可用的主机限定会话 Cookie。 */
    @GetMapping("/internal/dsh-web/connect")
    public void connect(@RequestParam String ticket, HttpServletResponse response) {
        String session = service.redeem(ticket);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Set-Cookie", ResponseCookie.from("__Host-mytools-dsh", session)
                .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(1800).build().toString());
        response.setStatus(303); response.setHeader("Location", "/");
    }

    /** 仅供持有代理密钥的 Nginx 子请求校验，凭据不进入浏览器响应。 */
    @GetMapping("/internal/dsh-web/authorize")
    public void authorize(@RequestHeader(value = "X-Dsh-Proxy-Key", required = false) String key,
                          @CookieValue(value = "__Host-mytools-dsh", required = false) String session,
                          HttpServletResponse response) {
        String cookie = service.authorize(key, session);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Dsh-Upstream-Cookie", cookie);
        response.setStatus(204);
    }

    /**
     * 仅供持有代理密钥的局域网入口兑换独立 Cookie。
     *
     * @param key 内部代理密钥。
     * @param ticket 一次性票据。
     * @param response 浏览器响应。
     */
    @GetMapping("/internal/dsh-web/lan/connect")
    public void connectLan(@RequestHeader(value = "X-Dsh-Proxy-Key", required = false) String key,
                           @RequestParam String ticket, HttpServletResponse response) {
        String session = service.redeemLan(key, ticket);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        // HTTP 局域网使用独立名称和会话空间，不改变公网 Cookie 的 Secure 限制。
        response.setHeader("Set-Cookie", ResponseCookie.from("mytools-dsh-lan", session)
                .httpOnly(true).secure(false).sameSite("Strict").path("/").maxAge(1800).build().toString());
        response.setStatus(303);
        response.setHeader("Location", "/");
    }

    /**
     * 只向持有代理密钥的局域网鉴权子请求返回上游凭据。
     *
     * @param key 内部代理密钥。
     * @param session 局域网浏览器会话。
     * @param response 内部代理响应。
     */
    @GetMapping("/internal/dsh-web/lan/authorize")
    public void authorizeLan(@RequestHeader(value = "X-Dsh-Proxy-Key", required = false) String key,
                             @CookieValue(value = "mytools-dsh-lan", required = false) String session,
                             HttpServletResponse response) {
        String cookie = service.authorizeLan(key, session);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Dsh-Upstream-Cookie", cookie);
        response.setStatus(204);
    }
}
