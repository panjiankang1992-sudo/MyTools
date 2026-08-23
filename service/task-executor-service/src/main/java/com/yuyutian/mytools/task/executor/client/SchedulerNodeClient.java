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
import java.util.Optional;
import java.util.UUID;

/**
 * 调度服务节点协议客户端。
 */
@Component
public class SchedulerNodeClient implements SchedulerClient {

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
    @Override
    public ExecutorNodeRegistration register(UUID instanceId) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", properties.nodeName());
        payload.put("instanceId", instanceId.toString());
        payload.put("capabilities", safeMap(properties.capabilities()));
        payload.put("labels", safeMap(properties.labels()));
        payload.put("maxConcurrentTasks", properties.maxConcurrentTasks());
        payload.put("clusterNames", properties.clusterNames() == null ? java.util.Set.of() : properties.clusterNames());
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
    @Override
    public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) throws IOException {
        sendJson("/api/v1/execution-topology/nodes/" + nodeId + "/heartbeat", Map.of(), Map.of(
                "X-Executor-Instance-Id", instanceId.toString(),
                "X-Running-Tasks", Integer.toString(runningTasks)
        ));
    }

    /**
     * 领取一个可执行任务。
     *
     * @param nodeId 节点标识
     * @param instanceId 启动实例标识
     * @return 可选任务租约
     * @throws IOException 网络或响应解析失败
     */
    @Override
    public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) throws IOException {
        Map<String, Object> payload = Map.of(
                "nodeId", nodeId.toString(),
                "instanceId", instanceId.toString(),
                "leaseSeconds", properties.leaseSeconds()
        );
        HttpResponse<String> response = post("/internal/v1/executions/claim", payload, Map.of());
        if (response.statusCode() == 204) {
            return Optional.empty();
        }
        requireSuccess(response);
        return Optional.of(objectMapper.readValue(response.body(), ClaimedTask.class));
    }

    /**
     * 续期一个任务执行租约。
     *
     * @param task 已领取任务
     * @return 租约状态
     * @throws IOException 网络或响应解析失败
     */
    @Override
    public ExecutionLease heartbeatExecution(ClaimedTask task) throws IOException {
        JsonNode response = sendJson("/internal/v1/executions/" + task.executionId() + "/heartbeat", Map.of(
                "leaseToken", task.leaseToken().toString(),
                "leaseSeconds", properties.leaseSeconds()
        ), Map.of());
        return objectMapper.treeToValue(response, ExecutionLease.class);
    }

    /**
     * 上报脚本步骤结果。
     *
     * @param task 已领取任务
     * @param step 脚本步骤
     * @param attempt 尝试次数
     * @param status 结果状态
     * @param exitCode 退出码
     * @param result 结构化结果
     * @param errorCode 错误码
     * @param errorMessage 错误摘要
     * @throws IOException 网络或响应解析失败
     */
    @Override
    public void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                           Map<String, Object> result, String errorCode, String errorMessage) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leaseToken", task.leaseToken().toString());
        payload.put("stepDefinitionId", step.stepDefinitionId().toString());
        payload.put("attempt", attempt);
        payload.put("status", status);
        payload.put("exitCode", exitCode);
        payload.put("result", result);
        payload.put("errorCode", errorCode);
        payload.put("errorMessage", errorMessage);
        sendJson("/internal/v1/executions/" + task.executionId() + "/steps/report", payload, Map.of());
    }

    /**
     * 完成任务执行。
     *
     * @param task 已领取任务
     * @param status 最终状态
     * @throws IOException 网络或响应解析失败
     */
    @Override
    public void complete(ClaimedTask task, String status) throws IOException {
        sendJson("/internal/v1/executions/" + task.executionId() + "/complete", Map.of(
                "leaseToken", task.leaseToken().toString(),
                "status", status
        ), Map.of());
    }

    private JsonNode sendJson(String path, Map<String, Object> payload, Map<String, String> headers) throws IOException {
        HttpResponse<String> response = post(path, payload, headers);
        requireSuccess(response);
        return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
    }

    private HttpResponse<String> post(String path, Map<String, Object> payload,
                                      Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(normalizedBaseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)));
        headers.forEach(builder::header);
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Scheduler request was interrupted", exception);
        }
    }

    private void requireSuccess(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Scheduler request failed with HTTP " + response.statusCode());
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
