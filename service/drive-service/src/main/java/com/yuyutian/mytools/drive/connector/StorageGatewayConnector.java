package com.yuyutian.mytools.drive.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.drive.model.DriveModels.IndexItem;
import com.yuyutian.mytools.drive.model.DriveModels.StorageOperationView;
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
import java.util.Map;

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

    /**
     * 幂等创建受控对象复制操作。
     *
     * @param idempotencyKey 幂等键
     * @param sourceProviderId 来源 Provider
     * @param sourcePath 来源路径
     * @param targetProviderId 目标 Provider
     * @param targetPath 目标路径
     * @return Storage 操作
     */
    public StorageOperationView copyObject(String idempotencyKey, UUID sourceProviderId, String sourcePath,
                                           UUID targetProviderId, String targetPath) {
        Map<String, Object> payload = Map.of(
                "idempotencyKey", idempotencyKey,
                "providerId", sourceProviderId,
                "operationType", "COPY_OBJECT",
                "sourcePath", sourcePath,
                "targetProviderId", targetProviderId,
                "targetPath", targetPath,
                "maximumObjects", 1);
        return operationRequest("api/internal/v1/storage/operations", "POST", payload);
    }

    /**
     * 幂等创建原生递归树复制操作。
     *
     * @param idempotencyKey 幂等键
     * @param sourceProviderId 来源 Provider
     * @param sourcePath 来源根路径
     * @param targetProviderId 目标 Provider
     * @param targetPath 目标根路径
     * @param maximumObjects 最大对象数
     * @return Storage 操作
     */
    public StorageOperationView copyTree(String idempotencyKey, UUID sourceProviderId, String sourcePath,
                                         UUID targetProviderId, String targetPath, int maximumObjects) {
        Map<String, Object> payload = Map.of(
                "idempotencyKey", idempotencyKey,
                "providerId", sourceProviderId,
                "operationType", "COPY_TREE_NATIVE",
                "sourcePath", sourcePath,
                "targetProviderId", targetProviderId,
                "targetPath", targetPath,
                "maximumObjects", maximumObjects);
        return operationRequest("api/internal/v1/storage/operations", "POST", payload);
    }

    /**
     * 幂等创建具备补偿状态机的递归移动操作。
     *
     * @param idempotencyKey 幂等键
     * @param sourceProviderId 来源 Provider
     * @param sourcePath 来源路径
     * @param targetProviderId 目标 Provider
     * @param targetPath 目标路径
     * @param maximumObjects 最大对象数
     * @return Storage 操作
     */
    public StorageOperationView moveTree(String idempotencyKey, UUID sourceProviderId, String sourcePath,
                                         UUID targetProviderId, String targetPath, int maximumObjects) {
        Map<String, Object> payload = Map.of(
                "idempotencyKey", idempotencyKey,
                "providerId", sourceProviderId,
                "operationType", "MOVE_TREE",
                "sourcePath", sourcePath,
                "targetProviderId", targetProviderId,
                "targetPath", targetPath,
                "maximumObjects", maximumObjects);
        return operationRequest("api/internal/v1/storage/operations", "POST", payload);
    }

    /**
     * 幂等创建非根目录树删除操作。
     *
     * @param idempotencyKey 幂等键
     * @param providerId Provider
     * @param path 非空相对路径
     * @param maximumObjects 最大对象数
     * @return Storage 操作
     */
    public StorageOperationView deleteTree(String idempotencyKey, UUID providerId, String path,
                                           int maximumObjects) {
        Map<String, Object> payload = Map.of(
                "idempotencyKey", idempotencyKey,
                "providerId", providerId,
                "operationType", "DELETE_TREE",
                "sourcePath", path,
                "maximumObjects", maximumObjects);
        return operationRequest("api/internal/v1/storage/operations", "POST", payload);
    }

    /**
     * 查询 Storage 操作。
     *
     * @param operationId 操作标识
     * @return Storage 操作
     */
    public StorageOperationView operation(UUID operationId) {
        return operationRequest("api/internal/v1/storage/operations/" + operationId, "GET", null);
    }

    /**
     * 请求取消 Storage 操作。
     *
     * @param operationId 操作标识
     * @return Storage 操作
     */
    public StorageOperationView cancel(UUID operationId) {
        return operationRequest("api/internal/v1/storage/operations/" + operationId + "/cancel", "POST", Map.of());
    }

    private StorageOperationView operationRequest(String relativePath, String method, Object payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(relativePath))
                    .timeout(Duration.ofMinutes(2)).header("Authorization", "Bearer " + internalToken);
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(
                        objectMapper.writeValueAsBytes(payload)));
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body().length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException("Storage Gateway operation failed");
            }
            return objectMapper.readValue(response.body(), StorageOperationView.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Storage Gateway operation interrupted", exception);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("Storage Gateway operation failed", exception);
        }
    }

    private String parent(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }
}
