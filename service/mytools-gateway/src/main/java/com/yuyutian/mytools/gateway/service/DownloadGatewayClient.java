package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.CreateHttpDownload;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.DownloadView;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.ResultItem;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.ResultSummary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 以可信主体调用下载接入服务的内部客户端。
 */
@Component
public class DownloadGatewayClient {
    private static final long DEFAULT_MAX_BYTES = 21_474_836_480L;
    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    /**
     * 创建下载 Gateway 客户端。
     *
     * @param restTemplate 有界 HTTP 客户端
     * @param properties Gateway 配置
     */
    public DownloadGatewayClient(RestTemplate restTemplate, GatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 创建属于可信主体的 HTTP 下载。
     */
    public DownloadView createHttp(CreateHttpDownload request, long ownerId, String correlationId) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("ownerId", ownerId);
        parameters.put("itemId", request.idempotencyKey());
        parameters.put("url", request.url());
        parameters.put("fileName", request.fileName());
        parameters.put("maxBytes", request.maxBytes() == null ? DEFAULT_MAX_BYTES : request.maxBytes());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", ownerId);
        body.put("idempotencyKey", "gateway:" + ownerId + ":" + request.idempotencyKey());
        body.put("sourceType", "GATEWAY_HTTP");
        body.put("sourceKey", ownerId + ":" + request.idempotencyKey());
        body.put("requestKind", "HTTP_ASSET");
        body.put("parameters", parameters);
        return exchange(root() + "/api/v1/download-requests", HttpMethod.POST,
                new HttpEntity<>(body, headers(correlationId)), MAP_TYPE, this::view);
    }

    /**
     * 查询属于可信主体的下载请求。
     */
    public DownloadView get(UUID requestId, long ownerId, String correlationId) {
        return exchange(ownerUrl(requestId, "", ownerId), HttpMethod.GET,
                new HttpEntity<>(headers(correlationId)), MAP_TYPE, this::view);
    }

    /**
     * 查询属于可信主体的下载结果汇总。
     */
    public ResultSummary summary(UUID requestId, long ownerId, String correlationId) {
        return exchange(ownerUrl(requestId, "/result-summary", ownerId), HttpMethod.GET,
                new HttpEntity<>(headers(correlationId)), MAP_TYPE, this::summary);
    }

    /**
     * 取消属于可信主体的下载请求。
     */
    public DownloadView cancel(UUID requestId, long ownerId, String correlationId) {
        return exchange(ownerUrl(requestId, "/cancel", ownerId), HttpMethod.POST,
                new HttpEntity<>(headers(correlationId)), MAP_TYPE, this::view);
    }

    private URI ownerUrl(UUID requestId, String suffix, long ownerId) {
        return UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/download-requests/"
                        + requestId + suffix).queryParam("ownerId", ownerId).build().encode().toUri();
    }

    private HttpHeaders headers(String correlationId) {
        if (properties.downloadToken() == null || properties.downloadToken().isBlank()) {
            throw new GatewayDownstreamException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.downloadToken());
        headers.set("X-Correlation-Id", correlationId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private <T> T exchange(Object url, HttpMethod method, HttpEntity<?> entity,
                           ParameterizedTypeReference<Map<String, Object>> type,
                           java.util.function.Function<Map<String, Object>, T> mapper) {
        try {
            Map<String, Object> body = restTemplate.exchange(url.toString(), method, entity, type).getBody();
            if (body == null) {
                throw new GatewayDownstreamException();
            }
            return mapper.apply(body);
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new GatewayNotFoundException();
            }
            int status = exception.getStatusCode().value();
            if (status == 400 || status == 409 || status == 422) {
                throw new GatewayBadRequestException();
            }
            throw new GatewayDownstreamException();
        } catch (GatewayBadRequestException | GatewayNotFoundException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new GatewayDownstreamException();
        }
    }

    private DownloadView view(Map<String, Object> body) {
        return new DownloadView(uuid(body.get("id")), text(body.get("status")),
                text(body.get("created_at")), text(body.get("updated_at")));
    }

    private ResultSummary summary(Map<String, Object> body) {
        Object rawItems = body.get("items");
        List<ResultItem> items = rawItems instanceof List<?> list ? list.stream()
                .filter(Map.class::isInstance).map(Map.class::cast).map(this::item).toList() : List.of();
        return new ResultSummary(uuid(body.get("downloadRequestId")), text(body.get("status")),
                integer(body.get("itemCount")), number(body.get("totalBytes")),
                text(body.get("collectionSha256")), text(body.get("contentSetSha256")), items);
    }

    private ResultItem item(Map<?, ?> body) {
        return new ResultItem(text(body.get("itemId")), text(body.get("fileName")),
                text(body.get("contentSha256")), number(body.get("sizeBytes")),
                text(body.get("storageUri")), nullableUuid(body.get("assetId")));
    }

    private UUID uuid(Object value) {
        UUID result = nullableUuid(value);
        if (result == null) {
            throw new GatewayDownstreamException();
        }
        return result;
    }

    private UUID nullableUuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String root() {
        return properties.downloadUrl().replaceAll("/+$", "");
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };
}
