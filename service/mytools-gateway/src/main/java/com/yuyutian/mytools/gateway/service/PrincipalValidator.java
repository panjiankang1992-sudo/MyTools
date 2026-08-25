package com.yuyutian.mytools.gateway.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按显式迁移模式校验旧会话或 Identity 会话。
 */
@Component
public class PrincipalValidator {

    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    /**
     * 创建统一主体校验器。
     */
    public PrincipalValidator(RestTemplate restTemplate, GatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 校验访问令牌并返回可信主体。
     */
    public GatewayPrincipal validate(String accessToken) {
        return switch (properties.identityMode()) {
            case LEGACY -> require(validateLegacy(accessToken));
            case IDENTITY -> require(validateIdentity(accessToken));
            case DUAL -> {
                ValidationResponse legacy = validateLegacy(accessToken);
                yield legacy.active() ? principal(legacy) : require(validateIdentity(accessToken));
            }
        };
    }

    private ValidationResponse validateLegacy(String token) {
        return post(properties.mytoolsUrl(), "/internal/v1/gateway/tokens/validate",
                properties.internalToken(), token);
    }

    private ValidationResponse validateIdentity(String token) {
        return post(properties.identityUrl(), "/internal/v1/identity/tokens/validate",
                properties.identityToken(), token);
    }

    private ValidationResponse post(String baseUrl, String path, String internalToken, String accessToken) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new GatewayUnauthorizedException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(internalToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ValidationResponse response = restTemplate.postForObject(baseUrl.replaceAll("/+$", "") + path,
                    new HttpEntity<>(Map.of("accessToken", accessToken), headers), ValidationResponse.class);
            if (response == null) {
                throw new GatewayUnauthorizedException();
            }
            return response;
        } catch (RestClientException exception) {
            throw new GatewayUnauthorizedException();
        }
    }

    private GatewayPrincipal require(ValidationResponse response) {
        if (!response.active() || response.userId() == null) {
            throw new GatewayUnauthorizedException();
        }
        return principal(response);
    }

    private GatewayPrincipal principal(ValidationResponse response) {
        return new GatewayPrincipal(response.userId(), response.username(),
                response.roles() == null ? List.of() : List.copyOf(response.roles()), response.sessionId());
    }

    /**
     * 下游认证服务统一响应。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidationResponse(boolean active, Long userId, String username, List<String> roles,
                                     UUID sessionId) {
    }
}
