package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaPage;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.ProgressRequest;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.ProgressView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.StartDirectoryScan;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.OperationView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.StartAnalysis;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 只使用可信主体访问 Media Library 的内部客户端。
 */
@Component
public class MediaGatewayClient {
    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    /**
     * 创建 Media Gateway 客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties Gateway 配置
     */
    public MediaGatewayClient(RestTemplate restTemplate, GatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 分页查询当前主体媒体。
     *
     * @param ownerId 所有者
     * @param afterId 起始标识
     * @param includeMissing 是否包含缺失项
     * @param limit 页大小
     * @param correlationId 关联标识
     * @return 媒体页
     */
    public MediaPage list(long ownerId, UUID afterId, boolean includeMissing, String mimePrefix, int limit,
                          String correlationId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/media/items")
                .queryParam("ownerId", ownerId)
                .queryParam("includeMissing", includeMissing)
                .queryParam("limit", limit);
        if (afterId != null) {
            builder.queryParam("afterId", afterId);
        }
        if (mimePrefix != null && !mimePrefix.isBlank()) {
            builder.queryParam("mimePrefix", mimePrefix);
        }
        return exchange(builder.toUriString(), HttpMethod.GET, null, MediaPage.class, correlationId);
    }

    /**
     * 查询当前主体媒体。
     *
     * @param ownerId 所有者
     * @param mediaId 媒体标识
     * @param correlationId 关联标识
     * @return 媒体
     */
    public MediaView view(long ownerId, UUID mediaId, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/media/items/" + mediaId)
                .queryParam("ownerId", ownerId).toUriString();
        return exchange(url, HttpMethod.GET, null, MediaView.class, correlationId);
    }

    /** 删除当前所有者媒体投影。 @param ownerId 所有者 @param mediaId 媒体标识 @param correlationId 关联标识 */
    public void delete(long ownerId, UUID mediaId, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/media/items/" + mediaId)
                .queryParam("ownerId", ownerId).toUriString();
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity(null, correlationId), Void.class);
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().value() == 404) throw new GatewayNotFoundException();
            if (exception.getStatusCode().is4xxClientError()) throw new GatewayBadRequestException();
            throw new GatewayDownstreamException();
        } catch (ResourceAccessException exception) {
            throw new GatewayDownstreamException();
        }
    }

    /**
     * 将媒体内容以恒定内存转发到客户端。
     */
    public void stream(long ownerId, UUID mediaId, boolean thumbnail, String range, HttpServletResponse response,
                       String correlationId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/media/items/" + mediaId
                    + "/content").queryParam("ownerId", ownerId).queryParam("thumbnail", thumbnail).toUriString();
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestProperty("Authorization", "Bearer " + properties.mediaToken());
            connection.setRequestProperty("X-Correlation-Id", correlationId);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (range != null && !range.isBlank()) {
                connection.setRequestProperty("Range", range);
            }
            connection.setConnectTimeout(properties.connectTimeoutMillis());
            connection.setReadTimeout(Math.max(properties.readTimeoutMillis(), 120000));
            int status = connection.getResponseCode();
            response.setStatus(status);
            copyHeader(connection, response, "Content-Type");
            copyHeader(connection, response, "Content-Length");
            copyHeader(connection, response, "Content-Range");
            copyHeader(connection, response, "Accept-Ranges");
            if (status < 200 || status >= 300) {
                connection.disconnect();
                return;
            }
            try (var input = connection.getInputStream(); var output = response.getOutputStream()) {
                input.transferTo(output);
            } finally {
                connection.disconnect();
            }
        } catch (Exception exception) {
            throw new GatewayDownstreamException();
        }
    }

    private void copyHeader(HttpURLConnection connection, HttpServletResponse response, String name) {
        String value = connection.getHeaderField(name);
        if (value != null && !value.isBlank()) {
            response.setHeader(name, value);
        }
    }

    /**
     * 写入当前主体播放进度。
     *
     * @param ownerId 所有者
     * @param mediaId 媒体标识
     * @param request 进度请求
     * @param correlationId 关联标识
     * @return 新进度
     */
    public ProgressView progress(long ownerId, UUID mediaId, ProgressRequest request,
                                 String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/media/items/" + mediaId
                        + "/progress").queryParam("ownerId", ownerId).toUriString();
        return exchange(url, HttpMethod.PUT, request, ProgressView.class, correlationId);
    }

    /** 创建目录扫描任务。 @param ownerId 所有者 @param request 请求 @param correlationId 关联标识 @return 操作 */
    public OperationView startDirectoryScan(long ownerId,StartDirectoryScan request,String correlationId) {
        String url=UriComponentsBuilder.fromHttpUrl(root()+"/internal/v1/media/operations/directory-scans")
            .queryParam("ownerId",ownerId).toUriString();
        return exchange(url,HttpMethod.POST,request,OperationView.class,correlationId);
    }

    /** 创建媒体分析任务。 @param ownerId 所有者 @param mediaId 媒体标识 @param request 请求 @param correlationId 关联标识 @return 操作 */
    public OperationView startAnalysis(long ownerId,UUID mediaId,StartAnalysis request,String correlationId) {
        String url=UriComponentsBuilder.fromHttpUrl(root()+"/internal/v1/media/items/"+mediaId+"/analysis-operations")
            .queryParam("ownerId",ownerId).toUriString();
        return exchange(url,HttpMethod.POST,request,OperationView.class,correlationId);
    }

    /** 查询媒体操作。 @param ownerId 所有者 @param operationId 操作 @param correlationId 关联标识 @return 操作 */
    public OperationView operation(long ownerId,UUID operationId,String correlationId) {
        String url=UriComponentsBuilder.fromHttpUrl(root()+"/internal/v1/media/operations/"+operationId)
            .queryParam("ownerId",ownerId).toUriString();
        return exchange(url,HttpMethod.GET,null,OperationView.class,correlationId);
    }

    /** 取消媒体操作。 @param ownerId 所有者 @param operationId 操作 @param correlationId 关联标识 @return 操作 */
    public OperationView cancel(long ownerId,UUID operationId,String correlationId) {
        String url=UriComponentsBuilder.fromHttpUrl(root()+"/internal/v1/media/operations/"+operationId+"/cancel")
            .queryParam("ownerId",ownerId).toUriString();
        return exchange(url,HttpMethod.POST,null,OperationView.class,correlationId);
    }

    private <T> T exchange(String url, HttpMethod method, Object body, Class<T> responseType,
                           String correlationId) {
        try {
            T value = restTemplate.exchange(url, method, entity(body, correlationId), responseType).getBody();
            if (value == null) {
                throw new GatewayDownstreamException();
            }
            return value;
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new GatewayNotFoundException();
            }
            if (exception.getStatusCode().is4xxClientError()) {
                throw new GatewayBadRequestException();
            }
            throw new GatewayDownstreamException();
        } catch (ResourceAccessException exception) {
            throw new GatewayDownstreamException();
        }
    }

    private HttpEntity<?> entity(Object body, String correlationId) {
        if (properties.mediaToken() == null || properties.mediaToken().isBlank()) {
            throw new GatewayUnauthorizedException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.mediaToken());
        headers.set("X-Correlation-Id", correlationId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String root() {
        return properties.mediaUrl().replaceAll("/+$", "");
    }
}
