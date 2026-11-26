package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.gateway.common.ErrorCode;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.RssGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RSS 双端用户入口采用有限路由白名单，拒绝任意内部路径代理。 */
@RestController
@RequestMapping({"/api/app/v1/rss", "/api/v1/rss"})
public class RssGatewayController {
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Map<String, String> ROUTES = Map.of(
            "GET", "/(?:capabilities|counts|subscriptions|subscriptions/" + UUID + "|entries|entries/[1-9][0-9]{0,18}|groups|opml/export)",
            "POST", "/(?:discover|subscriptions|subscriptions/" + UUID + "/refresh|entries/mark-read|groups|opml/(?:preview|import))",
            "PUT", "/(?:subscriptions/" + UUID + "|entries/[1-9][0-9]{0,18}/state|groups/" + UUID + ")",
            "DELETE", "/(?:subscriptions/" + UUID + "|groups/" + UUID + ")");
    private final RssGatewayClient client;
    /** 创建限定路由的代理。 */
    public RssGatewayController(RssGatewayClient client) { this.client = client; }
    /** 验证路径、方法和查询参数，owner 始终来自登录主体。 */
    @RequestMapping("/**")
    public ResponseEntity<Object> forward(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) throw new GatewayUnauthorizedException();
        String uri = request.getRequestURI();
        String prefix = uri.startsWith("/api/app/v1/rss") ? "/api/app/v1/rss" : "/api/v1/rss";
        String path = uri.substring(prefix.length());
        String pattern = ROUTES.get(request.getMethod());
        if (pattern == null || !path.matches(pattern)) return ResponseEntity.status(404).body(Map.of("code", ErrorCode.RSS_005.name(), "message", ErrorCode.RSS_005.name()));
        Set<String> allowed = request.getMethod().equals("GET") && path.equals("/entries")
                ? Set.of("subscriptionId", "groupId", "view", "q", "cursor", "limit") : Set.of();
        Map<String, String> query = new LinkedHashMap<>();
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!allowed.contains(parameter.getKey()) || parameter.getValue().length != 1 || parameter.getValue()[0].length() > 2048) return ResponseEntity.badRequest().body(Map.of("code", ErrorCode.RSS_001.name(), "message", ErrorCode.RSS_001.name()));
            query.put(parameter.getKey(), parameter.getValue()[0]);
        }
        return client.exchange(principal.userId(), path, HttpMethod.valueOf(request.getMethod()), query, body);
    }
}
