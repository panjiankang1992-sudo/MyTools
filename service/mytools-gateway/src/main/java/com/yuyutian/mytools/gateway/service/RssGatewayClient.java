package com.yuyutian.mytools.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.gateway.config.RssGatewayProperties;
import com.yuyutian.mytools.gateway.config.RssGatewayConfiguration.RssHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 只代理控制器白名单内的 RSS 用户操作，不转发客户端身份头。 */
@Component
public class RssGatewayClient {
    private final RestTemplate rest;
    private final RssGatewayProperties properties;
    private final ObjectMapper json;
    /** 注入覆盖 Feed 总时限的专用客户端。 */
    @Autowired
    public RssGatewayClient(RssHttpClient http, RssGatewayProperties properties, ObjectMapper json) { this(http.rest(), properties, json); }
    /** 创建固定目标的内部客户端。 */
    public RssGatewayClient(RestTemplate rest, RssGatewayProperties properties, ObjectMapper json) { this.rest = rest; this.properties = properties; this.json = json; }
    /** 仅将认证主体作为内部 owner，保留允许的业务状态和错误码。 */
    public ResponseEntity<Object> exchange(long owner, String path, HttpMethod method, Map<String, String> query, JsonNode body) {
        if (!properties.routeEnabled() || properties.token() == null || properties.token().length() < 32) throw new GatewayRouteDisabledException();
        var builder = UriComponentsBuilder.fromHttpUrl(properties.url().replaceAll("/+$", "") + "/internal/v1/rss" + path);
        // 查询值采用严格编码，不能把值中的分隔符变成新的参数。
        query.forEach((name, value) -> builder.queryParam(name, "{" + name + "}"));
        var uri = builder.encode().buildAndExpand(query).toUri();
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(properties.token());
        headers.set("X-RSS-Owner", Long.toString(owner)); headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            var response = rest.exchange(uri, method, new HttpEntity<>(body, headers), JsonNode.class);
            if (response.getBody() == null) throw new GatewayDownstreamException();
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (HttpStatusCodeException exception) {
            int status = exception.getStatusCode().value();
            if (status != 400 && status != 404 && status != 409 && status != 413 && status != 422 && status != 429 && status != 502) throw new GatewayDownstreamException();
            try {
                String code = json.readTree(exception.getResponseBodyAsString()).path("code").asText();
                if (!code.matches("RSS_(00[1-9]|012)")) throw new GatewayDownstreamException();
                return ResponseEntity.status(status).body(Map.of("code", code, "message", code));
            } catch (java.io.IOException invalid) { throw new GatewayDownstreamException(); }
        } catch (RestClientException exception) { throw new GatewayDownstreamException(); }
    }
}
