package com.yuyutian.mytools.dshconnector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.dshconnector.config.DshConnectorProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** 只访问固定回环 DSH 地址的受限 JSON-RPC 客户端。 */
@Component
public class DshRpcClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "session.create", "session.prompt", "session.history", "session.cancel");
    private final ObjectMapper objectMapper;
    private final DshConnectorProperties properties;
    private final URI baseUri;
    private final HttpClient httpClient;

    /** 创建受限 RPC 客户端。 */
    public DshRpcClient(ObjectMapper objectMapper, DshConnectorProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUri = validateBaseUri(properties.getBaseUrl());
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 调用白名单内的一元方法并返回 result.value。 */
    public JsonNode call(String method, JsonNode payload) {
        if (!properties.isEnabled() || !ALLOWED_METHODS.contains(method)
                || payload == null || !payload.isObject()) {
            throw new IllegalStateException("DSH RPC is unavailable or invalid");
        }
        String rpcId = UUID.randomUUID().toString();
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "client-request");
        envelope.put("rpcId", rpcId);
        envelope.put("method", method);
        envelope.set("payload", payload);
        JsonNode response = send("/api/" + method, envelope);
        JsonNode result = response.path("result");
        if (!"server-response".equals(response.path("type").asText())
                || !rpcId.equals(response.path("rpcId").asText())
                || !result.path("ok").asBoolean(false)) {
            throw new IllegalStateException("DSH RPC response is invalid");
        }
        return result.path("value");
    }

    private JsonNode send(String path, JsonNode envelope) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(envelope);
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() != 200 || bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("DSH RPC request failed");
                }
                return objectMapper.readTree(bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DSH RPC request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("DSH RPC request failed", exception);
        }
    }

    private URI validateBaseUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                throw new IllegalArgumentException("DSH base URL is invalid");
            }
            return URI.create(uri.toString().replaceAll("/+$", "") + "/");
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("DSH base URL must use loopback HTTP", exception);
        }
    }
}
