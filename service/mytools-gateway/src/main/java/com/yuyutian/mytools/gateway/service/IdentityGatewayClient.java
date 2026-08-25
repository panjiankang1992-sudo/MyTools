package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.LoginRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.RefreshRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.TokenPair;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.SessionView;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;
import java.util.List;

/**
 * 只转发稳定认证字段的 Identity 客户端。
 */
@Component
public class IdentityGatewayClient {
    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    /**
     * 创建 Identity Gateway 客户端。
     *
     * @param restTemplate 有界 HTTP 客户端
     * @param properties Gateway 配置
     */
    public IdentityGatewayClient(RestTemplate restTemplate, GatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 调用独立身份服务登录。
     *
     * @param request 已校验登录请求
     * @param correlationId 关联标识
     * @return 令牌对
     */
    public TokenPair login(LoginRequest request, String correlationId) {
        return post("/api/v1/identity/login",
                new LoginRequest(request.username(), request.password(), request.deviceId()), correlationId);
    }

    /**
     * 调用独立身份服务轮换刷新令牌。
     *
     * @param request 已校验刷新请求
     * @param correlationId 关联标识
     * @return 新令牌对
     */
    public TokenPair refresh(RefreshRequest request, String correlationId) {
        return post("/api/v1/identity/refresh", new RefreshRequest(request.refreshToken()), correlationId);
    }

    /**
     * 撤销已验证主体的当前会话。
     *
     * @param sessionId 已验证会话标识
     * @param correlationId 关联标识
     */
    public void logout(UUID sessionId, String correlationId) {
        if (properties.identityToken() == null || properties.identityToken().isBlank()) {
            throw new GatewayDownstreamException();
        }
        URI url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/identity/sessions/"
                        + sessionId + "/revoke")
                .queryParam("reason", "USER_LOGOUT").build().encode().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.identityToken());
        headers.set("X-Correlation-Id", correlationId);
        try {
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        } catch (RestClientException exception) {
            throw new GatewayDownstreamException();
        }
    }

    /** 查询当前用户会话。 @param userId 用户 @param correlationId 关联标识 @return 会话 */
    public List<SessionView> sessions(long userId, String correlationId) {
        HttpHeaders headers = internalHeaders(correlationId);
        try {
            var response = restTemplate.exchange(root() + "/internal/v1/identity/users/" + userId + "/sessions",
                    HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<List<SessionView>>() { });
            return response.getBody() == null ? List.of() : response.getBody();
        } catch (RestClientException exception) {
            throw new GatewayDownstreamException();
        }
    }

    /** 撤销当前用户会话。 @param userId 用户 @param sessionId 会话 @param correlationId 关联标识 */
    public void revoke(long userId, UUID sessionId, String correlationId) {
        try {
            restTemplate.exchange(root() + "/internal/v1/identity/users/" + userId + "/sessions/" + sessionId
                    + "/revoke", HttpMethod.POST, new HttpEntity<>(internalHeaders(correlationId)), Void.class);
        } catch (HttpClientErrorException.BadRequest exception) {
            throw new GatewayBadRequestException();
        } catch (RestClientException exception) {
            throw new GatewayDownstreamException();
        }
    }

    private HttpHeaders internalHeaders(String correlationId) {
        if (properties.identityToken() == null || properties.identityToken().isBlank()) {
            throw new GatewayDownstreamException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.identityToken());
        headers.set("X-Correlation-Id", correlationId);
        return headers;
    }

    private TokenPair post(String path, Object body, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);
        try {
            TokenPair response = restTemplate.postForObject(root() + path,
                    new HttpEntity<>(body, headers), TokenPair.class);
            if (response == null) {
                throw new GatewayDownstreamException();
            }
            return response;
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new GatewayUnauthorizedException();
            }
            if (exception.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new GatewayBadRequestException();
            }
            throw new GatewayDownstreamException();
        } catch (RestClientException exception) {
            throw new GatewayDownstreamException();
        }
    }

    private String root() {
        return properties.identityUrl().replaceAll("/+$", "");
    }
}
