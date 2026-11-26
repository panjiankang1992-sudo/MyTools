package com.yuyutian.mytools.gateway.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按显式迁移模式校验旧会话或 Identity 会话。
 */
@Component
public class PrincipalValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrincipalValidator.class);

    /** 内部校验目标只有固定名称，日志不使用实际服务地址。 */
    private enum ValidationTarget { LEGACY, IDENTITY }

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
                properties.internalToken(), token, ValidationTarget.LEGACY);
    }

    private ValidationResponse validateIdentity(String token) {
        return post(properties.identityUrl(), "/internal/v1/identity/tokens/validate",
                properties.identityToken(), token, ValidationTarget.IDENTITY);
    }

    private ValidationResponse post(String baseUrl, String path, String internalToken, String accessToken,
                                    ValidationTarget target) {
        long started = System.nanoTime();
        boolean completed = false;
        boolean active = false;
        int status = 0;
        String module = observationModule();
        String trace = observationTrace();
        LOGGER.info("Gateway auth phase=rpc-start module={} trace={} target={}", module, trace, target);
        try {
            if (internalToken == null || internalToken.isBlank()) {
                throw new GatewayUnauthorizedException();
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(internalToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ValidationResponse response = restTemplate.postForObject(baseUrl.replaceAll("/+$", "") + path,
                    new HttpEntity<>(Map.of("accessToken", accessToken), headers), ValidationResponse.class);
            // 只记录认证结果的布尔值，不记录主体字段、响应内容或校验异常消息。
            completed = response != null;
            active = response != null && response.active() && response.userId() != null;
            if (response == null) {
                throw new GatewayUnauthorizedException();
            }
            return response;
        } catch (RestClientException exception) {
            if (exception instanceof RestClientResponseException responseException) {
                status = responseException.getStatusCode().value();
            }
            throw new GatewayUnauthorizedException();
        } finally {
            // postForObject 不暴露成功状态码，未知状态保留 0，不伪造成功的 HTTP 状态。
            LOGGER.info("Gateway auth phase=rpc-complete module={} trace={} target={} completed={} active={} status={} elapsedMs={}",
                    module, trace, target, completed, active, status,
                    Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        }
    }

    private String observationModule() {
        String value = MDC.get("gatewayObservationModule");
        if (value != null && List.of("CONNECTIVITY", "IDENTITY_SELF", "READER", "MEDIA", "OTHER").contains(value)) {
            return value;
        }
        return "OTHER";
    }

    private String observationTrace() {
        String value = MDC.get("gatewayObservationTrace");
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                ? value : "none";
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
