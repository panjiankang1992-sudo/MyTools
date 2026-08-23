package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.LoginRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.RefreshRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.TokenPair;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

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
