package com.yuyutian.mytools.task.executor.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.runtime.ScriptReleaseVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 调度服务节点协议客户端。
 */
@Component
public class SchedulerNodeClient implements SchedulerClient {

    static final String INTERNAL_TOKEN_HEADER = "X-Task-Internal-Token";
    static final String SERVICE_ID_HEADER = "X-Task-Service-Id";
    static final String SERVICE_ID = "task-executor-service";

    private final ExecutorProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ScriptReleaseVerifier releaseVerifier;
    private final AtomicReference<UUID> pendingClaimRequestId = new AtomicReference<>();

    /**
     * 创建调度服务节点协议客户端。
     *
     * @param properties 执行节点配置
     * @param objectMapper JSON 映射器
     */
    @Autowired
    public SchedulerNodeClient(ExecutorProperties properties, ObjectMapper objectMapper,
                               ScriptReleaseVerifier releaseVerifier) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                releaseVerifier);
    }

    /**
     * 创建兼容旧调用方的调度服务客户端。
     *
     * @param properties 执行节点配置
     * @param objectMapper JSON 映射器
     */
    public SchedulerNodeClient(ExecutorProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), null);
    }

    SchedulerNodeClient(ExecutorProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this(properties, objectMapper, httpClient, null);
    }

    SchedulerNodeClient(ExecutorProperties properties, ObjectMapper objectMapper, HttpClient httpClient,
                        ScriptReleaseVerifier releaseVerifier) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.releaseVerifier = releaseVerifier;
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
        Map<String, Object> capabilities = new LinkedHashMap<>(safeMap(properties.capabilities()));
        if (releaseVerifier != null && !releaseVerifier.releaseDigests().isEmpty()) {
            capabilities.put("scriptReleases", releaseVerifier.releaseDigests());
        }
        payload.put("capabilities", Map.copyOf(capabilities));
        payload.put("labels", flattenedLabels(properties.labels()));
        payload.put("maxConcurrentTasks", properties.maxConcurrentTasks());
        payload.put("clusterNames", properties.clusterNames() == null ? java.util.Set.of() : properties.clusterNames());
        JsonNode response = sendJson("/api/v1/execution-topology/nodes/register", payload, Map.of());
        return new ExecutorNodeRegistration(
                UUID.fromString(response.path("id").asText()),
                response.path("name").asText(),
                response.path("instanceId").asText()
        );
    }

    static Map<String, Object> flattenedLabels(Map<String, Object> labels) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        flattenLabels("", labels == null ? Map.of() : labels, flattened);
        return Map.copyOf(flattened);
    }

    private static void flattenLabels(String prefix, Map<String, Object> labels,
                                      Map<String, Object> flattened) {
        for (Map.Entry<String, Object> entry : labels.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                nested.forEach((nestedKey, value) -> normalized.put(String.valueOf(nestedKey), value));
                flattenLabels(key, normalized, flattened);
            } else {
                flattened.put(key, entry.getValue());
            }
        }
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
     * 更新节点调度状态。
     */
    @Override
    public void updateNodeStatus(UUID nodeId, String status, String reason) throws IOException {
        sendPatchJson("/api/v1/execution-topology/nodes/" + nodeId + "/status", Map.of(
                "status", status,
                "reason", reason
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
        UUID claimRequestId = pendingClaimRequestId.updateAndGet(
                current -> current == null ? UUID.randomUUID() : current);
        Map<String, Object> payload = Map.of(
                "nodeId", nodeId.toString(),
                "instanceId", instanceId.toString(),
                "claimRequestId", claimRequestId.toString(),
                "leaseSeconds", properties.leaseSeconds()
        );
        HttpResponse<String> response = post("/internal/v1/executions/claim", payload, Map.of());
        if (response.statusCode() == 204) {
            pendingClaimRequestId.compareAndSet(claimRequestId, null);
            return Optional.empty();
        }
        requireSuccess(response);
        ClaimedTask claimedTask = objectMapper.readValue(response.body(), ClaimedTask.class);
        try {
            claimedTask.verifyDefinitionDigest();
        } catch (IllegalArgumentException exception) {
            throw new IOException("Scheduler returned an invalid task definition digest", exception);
        }
        pendingClaimRequestId.compareAndSet(claimRequestId, null);
        return Optional.of(claimedTask);
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
        reportStep(task, step, attempt, status, exitCode, result, errorCode, errorMessage, Map.of());
    }

    /**
     * 上报带日志索引的脚本步骤结果。
     */
    @Override
    public void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                           Map<String, Object> result, String errorCode, String errorMessage,
                           Map<String, Object> logIndex) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportRequestId", stableRequestId("step", task.executionId(),
                step.stepDefinitionId(), Integer.toString(attempt)).toString());
        payload.put("leaseToken", task.leaseToken().toString());
        payload.put("stepDefinitionId", step.stepDefinitionId().toString());
        payload.put("attempt", attempt);
        payload.put("status", status);
        payload.put("exitCode", exitCode);
        payload.put("result", result);
        payload.put("errorCode", errorCode);
        payload.put("errorMessage", errorMessage);
        payload.put("logIndex", safeMap(logIndex));
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
        complete(task, ExecutionCompletion.withoutCompensation(status));
    }

    /**
     * 完成执行并上报补偿结果。
     *
     * @param task 已领取任务
     * @param completion 执行终态及补偿结果
     * @throws IOException 网络或响应解析失败
     */
    @Override
    public void complete(ClaimedTask task, ExecutionCompletion completion) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("completionRequestId", stableRequestId("complete", task.executionId(), completion.status(),
                completion.compensationStatus(), completion.compensationRequired(),
                completion.compensationErrorCode()).toString());
        payload.put("leaseToken", task.leaseToken().toString());
        payload.put("status", completion.status());
        payload.put("compensationStatus", completion.compensationStatus());
        payload.put("compensationRequired", completion.compensationRequired());
        payload.put("compensationErrorCode", completion.compensationErrorCode());
        sendJson("/internal/v1/executions/" + task.executionId() + "/complete", payload, Map.of());
    }

    private UUID stableRequestId(String operation, Object... parts) {
        StringBuilder value = new StringBuilder(operation);
        for (Object part : parts) {
            value.append(':').append(part);
        }
        return UUID.nameUUIDFromBytes(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode sendJson(String path, Map<String, Object> payload, Map<String, String> headers) throws IOException {
        HttpResponse<String> response = post(path, payload, headers);
        requireSuccess(response);
        return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
    }

    private JsonNode sendPatchJson(String path, Map<String, Object> payload) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(normalizedBaseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(SERVICE_ID_HEADER, SERVICE_ID)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(writeJson(payload)));
        if (properties.internalToken() != null && !properties.internalToken().isBlank()) {
            builder.header(INTERNAL_TOKEN_HEADER, properties.internalToken());
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            requireSuccess(response);
            return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Scheduler request was interrupted", exception);
        }
    }

    private HttpResponse<String> post(String path, Map<String, Object> payload,
                                      Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(normalizedBaseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(SERVICE_ID_HEADER, SERVICE_ID)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)));
        if (properties.internalToken() != null && !properties.internalToken().isBlank()) {
            builder.header(INTERNAL_TOKEN_HEADER, properties.internalToken());
        }
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
            String errorCode = "HTTP_" + response.statusCode();
            try {
                JsonNode body = objectMapper.readTree(response.body());
                if (body.hasNonNull("code")) {
                    errorCode = body.path("code").asText();
                }
            } catch (JsonProcessingException ignored) {
                // 非 JSON 错误响应仍按 HTTP 状态分类。
            }
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            throw new SchedulerClientException(response.statusCode(), errorCode, retryable);
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
