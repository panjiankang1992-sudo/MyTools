package com.yuyutian.mytools.dsh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.dsh.config.DshProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 仅允许访问固定回环 DSH 地址的 JSON-RPC 客户端。
 */
@Service
@Slf4j
public class DshRpcClient {

    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final DshProperties properties;
    private final URI baseUri;
    private final HttpClient httpClient;

    /**
     * 创建 DSH RPC 客户端。
     *
     * @param objectMapper JSON序列化器
     * @param properties DSH配置
     */
    public DshRpcClient(ObjectMapper objectMapper, DshProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUri = validateBaseUri(properties.getBaseUrl());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 调用允许的 DSH 一元方法。
     *
     * @param method DSH方法名
     * @param payload 业务请求体
     * @return result.value
     */
    public JsonNode call(String method, JsonNode payload) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.DSH_001);
        }
        if (!isAllowedMethod(method) || payload == null || !payload.isObject()) {
            throw new BusinessException(ErrorCode.DSH_002);
        }
        String rpcId = UUID.randomUUID().toString();
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "client-request");
        envelope.put("rpcId", rpcId);
        envelope.put("method", method);
        envelope.set("payload", payload);
        JsonNode response = send("/api/" + method, envelope);
        if (!"server-response".equals(response.path("type").asText())
                || !rpcId.equals(response.path("rpcId").asText()) || !response.path("result").isObject()) {
            throw new BusinessException(ErrorCode.DSH_004);
        }
        JsonNode result = response.path("result");
        if (!result.path("ok").asBoolean(false)) {
            throw new BusinessException(ErrorCode.DSH_002);
        }
        return result.path("value");
    }

    /**
     * 返回已验证的 DSH 基础地址。
     *
     * @return 回环HTTP地址
     */
    public URI baseUri() {
        return baseUri;
    }

    /**
     * 回复 DSH 下行事件中的待处理交互。
     *
     * @param rpcId DSH下行交互ID
     * @param value 经校验的回复值
     */
    public void respond(String rpcId, JsonNode value) {
        if (!properties.isEnabled() || rpcId == null || !rpcId.matches("[0-9a-fA-F-]{36}")
                || value == null || !value.isObject()) {
            throw new BusinessException(ErrorCode.DSH_002);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ok", true);
        result.set("value", value);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "client-response");
        envelope.put("rpcId", rpcId);
        envelope.set("result", result);
        JsonNode receipt = send("/api/respond", envelope);
        if (!receipt.path("accepted").asBoolean(false)) {
            throw new BusinessException(ErrorCode.DSH_007);
        }
    }

    private JsonNode send(String path, JsonNode envelope) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(envelope);
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() != 200 || bytes.length > MAX_RESPONSE_BYTES) {
                    throw new BusinessException(ErrorCode.DSH_003);
                }
                return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("DSH RPC调用被中断：path={}", path);
            throw new BusinessException(ErrorCode.DSH_003);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            log.warn("DSH RPC调用失败：path={}, type={}, reason={}", path,
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw new BusinessException(ErrorCode.DSH_003);
        }
    }

    private boolean isAllowedMethod(String method) {
        return method.equals("host.describe") || method.equals("session.list") || method.equals("session.create")
                || method.equals("session.history") || method.equals("session.prompt")
                || method.equals("session.cancel") || method.equals("session.models")
                || method.equals("session.selectModel") || method.equals("session.rename");
    }

    private URI validateBaseUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !isLoopback(uri.getHost())) {
                throw new IllegalArgumentException("DSH base URL must use loopback HTTP");
            }
            return URI.create(uri.toString().replaceAll("/+$", "") + "/");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DSH base URL must be a fixed loopback HTTP URL", exception);
        }
    }

    private boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException exception) {
            return false;
        }
    }
}
