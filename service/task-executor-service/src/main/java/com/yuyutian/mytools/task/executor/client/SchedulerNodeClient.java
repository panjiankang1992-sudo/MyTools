package com.yuyutian.mytools.task.executor.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 调度服务节点协议客户端。
 */
@Component
public class SchedulerNodeClient {

    private final ExecutorProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 创建调度服务节点协议客户端。
     *
     * @param properties 执行节点配置
     * @param objectMapper JSON 映射器
     */
    public SchedulerNodeClient(ExecutorProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    SchedulerNodeClient(ExecutorProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 注册执行节点。
     *
     * @param instanceId 本次启动实例标识
     * @return 注册信息
     * @throws IOException 网络或响应解析失败
     */
    public ExecutorNodeRegistration register(UUID instanceId) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", properties.nodeName());
        payload.put("instanceId", instanceId.toString());
        payload.put("capabilities", safeMap(properties.capabilities()));
        payload.put("labels", safeMap(properties.labels()));
        payload.put("maxConcurrentTasks", properties.maxConcurrentTasks());
        JsonNode response = sendJson("/api/v1/execution-topology/nodes/register", payload, Map.of());
        return new ExecutorNodeRegistration(
                UUID.fromString(response.path("id").asText()),
                response.path("name").asText(),
                response.path("instanceId").asText()
        );
    }

    /**
     * 上报节点心跳。
     *
     * @param nodeId 节点标识
     * @param instanceId 本次启动实例标识
     * @param runningTasks 运行任务数
     * @throws IOException 网络或响应解析失败
     */
    public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) throws IOException {
        sendJson("/api/v1/execution-topology/nodes/" + nodeId + "/heartbeat", Map.of(), Map.of(
                "X-Executor-Instance-Id", instanceId.toString(),
                "X-Running-Tasks", Integer.toString(runningTasks)
        ));
    }

    private JsonNode sendJson(String path, Map<String, Object> payload, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(normalizedBaseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)));
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Scheduler request failed with HTTP " + response.statusCode());
            }
            return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Scheduler request was interrupted", exception);
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Scheduler request cannot be serialized", exception);
        }
    }

    private String normalizedBaseUrl() {
        String value = properties.schedulerUrl();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }
}
