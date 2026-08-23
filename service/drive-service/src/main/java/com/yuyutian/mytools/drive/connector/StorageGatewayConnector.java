package com.yuyutian.mytools.drive.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.drive.model.DriveModels.IndexItem;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 通过 Storage Gateway 读取远端目录的连接器。
 */
@Component
public class StorageGatewayConnector implements DirectoryConnector {
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String configuredUrl;
    private final String internalToken;
    private URI baseUri;

    /**
     * 创建 Storage Gateway 连接器。
     *
     * @param objectMapper JSON 映射器
     * @param configuredUrl Gateway 地址
     * @param internalToken 内部令牌
     */
    public StorageGatewayConnector(ObjectMapper objectMapper,
                                   @Value("${drive.storage-gateway-url:http://127.0.0.1:23240}") String configuredUrl,
                                   @Value("${drive.storage-internal-token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.configuredUrl = configuredUrl;
        this.internalToken = internalToken;
    }

    /**
     * 验证服务端 Gateway 基础地址。
     */
    @PostConstruct
    public void validateConfiguration() {
        URI candidate = URI.create(configuredUrl);
        if (!("http".equals(candidate.getScheme()) || "https".equals(candidate.getScheme()))
                || candidate.getHost() == null || candidate.getUserInfo() != null
                || candidate.getQuery() != null || candidate.getFragment() != null) {
            throw new IllegalStateException("Storage Gateway URL is invalid");
        }
        baseUri = URI.create(configuredUrl.endsWith("/") ? configuredUrl : configuredUrl + "/");
    }

    /**
     * 通过绑定的 Provider UUID 列出目录。
     *
     * @param providerKey Provider UUID
     * @param path 相对路径
     * @return 索引候选
     */
    @Override
    public List<IndexItem> list(String providerKey, String path) {
        UUID providerId = UUID.fromString(providerKey);
        String query = URLEncoder.encode(path, StandardCharsets.UTF_8);
        URI uri = baseUri.resolve("api/internal/v1/storage/providers/" + providerId + "/objects?path=" + query);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + internalToken).GET().build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body().length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException("Storage Gateway list failed");
            }
            JsonNode values = objectMapper.readTree(response.body());
            if (!values.isArray() || values.size() > 10000) {
                throw new IllegalStateException("Storage Gateway list response is invalid");
            }
            List<IndexItem> items = new ArrayList<>();
            for (JsonNode value : values) {
                String remotePath = value.path("path").asText();
                String parentPath = parent(remotePath);
                Instant modifiedAt = value.hasNonNull("modifiedAt")
                        ? Instant.parse(value.path("modifiedAt").asText()) : null;
                items.add(new IndexItem(null, remotePath, parentPath, value.path("name").asText(), null,
                        value.path("sizeBytes").asLong(), value.path("directory").asBoolean(), modifiedAt,
                        value.hasNonNull("contentSha256") ? value.path("contentSha256").asText() : null));
            }
            return List.copyOf(items);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Storage Gateway list interrupted", exception);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("Storage Gateway list failed", exception);
        }
    }

    private String parent(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }
}
