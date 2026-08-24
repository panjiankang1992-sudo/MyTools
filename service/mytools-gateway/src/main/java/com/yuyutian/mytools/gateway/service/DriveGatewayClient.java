package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.OperationView;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.RefreshIndexRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 只使用可信主体查询 Drive 索引的内部客户端。
 */
@Component
public class DriveGatewayClient {
    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    /**
     * 创建 Drive Gateway 客户端。
     *
     * @param restTemplate 有界 HTTP 客户端
     * @param properties Gateway 配置
     */
    public DriveGatewayClient(RestTemplate restTemplate, GatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 查询当前主体拥有账户的直接子项。
     *
     * @param accountId Drive 账户标识
     * @param ownerId 可信主体标识
     * @param parentPath 父路径
     * @param correlationId 关联标识
     * @return 索引子项
     */
    public List<Map<String, Object>> items(UUID accountId, long ownerId, String parentPath,
                                           String correlationId) {
        URI url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/drive/accounts/"
                        + accountId + "/items")
                .queryParam("ownerId", ownerId).queryParam("parentPath", parentPath)
                .build().encode().toUri();
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(correlationId),
                new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        return response.getBody() == null ? List.of() : response.getBody();
    }

    /** 创建账户索引刷新任务。 @param accountId 账户标识 @param ownerId 所有者 @param request 请求 @param correlationId 关联标识 @return 操作 */
    public OperationView refreshIndex(UUID accountId,long ownerId,RefreshIndexRequest request,String correlationId) {
        URI url=UriComponentsBuilder.fromHttpUrl(root()+"/internal/v1/drive/accounts/"+accountId+"/refresh-index")
            .queryParam("ownerId",ownerId).build().encode().toUri();
        return restTemplate.exchange(url,HttpMethod.POST,entity(request,correlationId),OperationView.class).getBody();
    }

    /** 查询操作。 @param operationId 操作标识 @param ownerId 所有者 @param correlationId 关联标识 @return 操作 */
    public OperationView operation(UUID operationId,long ownerId,String correlationId) {
        return exchangeOperation(operationId,ownerId,HttpMethod.GET,correlationId);
    }

    /** 取消操作。 @param operationId 操作标识 @param ownerId 所有者 @param correlationId 关联标识 @return 操作 */
    public OperationView cancel(UUID operationId,long ownerId,String correlationId) {
        return exchangeOperation(operationId,ownerId,HttpMethod.POST,correlationId);
    }

    private OperationView exchangeOperation(UUID operationId,long ownerId,HttpMethod method,String correlationId) {
        String suffix=method==HttpMethod.POST?"/cancel":"";
        URI url=UriComponentsBuilder.fromHttpUrl(root()+"/internal/v1/drive/operations/"+operationId+suffix)
            .queryParam("ownerId",ownerId).build().encode().toUri();
        return restTemplate.exchange(url,method,entity(Map.of(),correlationId),OperationView.class).getBody();
    }

    private HttpEntity<Void> entity(String correlationId) {
        if (properties.driveToken() == null || properties.driveToken().isBlank()) {
            throw new GatewayUnauthorizedException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.driveToken());
        headers.set("X-Correlation-Id", correlationId);
        return new HttpEntity<>(headers);
    }

    private <T> HttpEntity<T> entity(T body,String correlationId) {
        HttpHeaders headers=headers(correlationId); headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body,headers);
    }

    private HttpHeaders headers(String correlationId) {
        if (properties.driveToken() == null || properties.driveToken().isBlank()) {
            throw new GatewayUnauthorizedException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.driveToken());
        headers.set("X-Correlation-Id", correlationId);
        return headers;
    }

    private String root() {
        return properties.driveUrl().replaceAll("/+$", "");
    }
}
