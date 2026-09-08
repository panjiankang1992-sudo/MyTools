package com.yuyutian.mytools.task.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

/**
 * 独立领域服务调用 Scheduler 的公共客户端。
 */
public final class TaskSchedulerClient {

    public static final String BUSINESS_TOKEN_HEADER = "X-Task-Business-Token";
    public static final String SERVICE_ID_HEADER = "X-Task-Service-Id";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 创建公共客户端。
     *
     * @param restClient 已配置 Scheduler 基础地址的 HTTP 客户端
     * @param objectMapper JSON 映射器
     * @param businessToken 业务服务令牌
     */
    public TaskSchedulerClient(RestClient restClient, ObjectMapper objectMapper, String businessToken) {
        this(restClient, objectMapper, businessToken, "");
    }

    /**
     * 创建携带独立服务身份的公共客户端。
     *
     * @param restClient 已配置 Scheduler 基础地址的 HTTP 客户端
     * @param objectMapper JSON 映射器
     * @param businessToken 业务服务独立令牌
     * @param serviceId 业务服务身份
     */
    public TaskSchedulerClient(RestClient restClient, ObjectMapper objectMapper, String businessToken,
                               String serviceId) {
        RestClient.Builder builder = restClient.mutate();
        if (businessToken != null && !businessToken.isBlank()) {
            builder.defaultHeader(BUSINESS_TOKEN_HEADER, businessToken);
        }
        if (serviceId != null && !serviceId.isBlank()) {
            builder.defaultHeader(SERVICE_ID_HEADER, serviceId);
        }
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等创建任务实例。
     *
     * @param request 创建请求
     * @return 任务实例快照
     */
    public TaskInstanceSnapshot create(CreateTaskRequest request) {
        return invoke(() -> restClient.post().uri("/api/v1/task-instances")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
                .body(TaskInstanceSnapshot.class));
    }

    /**
     * 查询任务实例。
     *
     * @param taskId 任务标识
     * @return 任务实例快照
     */
    public TaskInstanceSnapshot get(UUID taskId) {
        return invoke(() -> restClient.get().uri("/api/v1/task-instances/{id}", taskId)
                .retrieve().body(TaskInstanceSnapshot.class));
    }

    /**
     * 请求取消任务实例。
     *
     * @param taskId 任务标识
     * @return 取消后的任务实例快照
     */
    public TaskInstanceSnapshot cancel(UUID taskId) {
        return invoke(() -> restClient.post().uri("/api/v1/task-instances/{id}/cancel", taskId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve()
                .body(TaskInstanceSnapshot.class));
    }

    /**
     * 查询任务执行结果。
     *
     * @param taskId 任务标识
     * @return 任务执行结果
     */
    public TaskExecutionResultSnapshot getResults(UUID taskId) {
        return invoke(() -> restClient.get().uri("/api/v1/task-instances/{id}/results", taskId)
                .retrieve().body(TaskExecutionResultSnapshot.class));
    }

    private <T> T invoke(ClientInvocation<T> invocation) {
        try {
            T response = invocation.execute();
            if (response == null) {
                throw new TaskSchedulerClientException(0, "EMPTY_RESPONSE", true,
                        "Task Scheduler response body is empty", null);
            }
            return response;
        } catch (TaskSchedulerClientException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw structuredException(exception);
        } catch (RestClientException exception) {
            throw new TaskSchedulerClientException(0, "NETWORK_ERROR", true,
                    "Task Scheduler request failed", exception);
        }
    }

    private TaskSchedulerClientException structuredException(RestClientResponseException exception) {
        int statusCode = exception.getStatusCode().value();
        String errorCode = "HTTP_" + statusCode;
        String message = "Task Scheduler rejected the request";
        try {
            JsonNode body = objectMapper.readTree(exception.getResponseBodyAsString());
            if (body.hasNonNull("code")) {
                errorCode = body.path("code").asText();
            }
            if (body.hasNonNull("message")) {
                message = body.path("message").asText();
            }
        } catch (JsonProcessingException ignored) {
            // 非 JSON 响应保留 HTTP 派生错误码。
        }
        return new TaskSchedulerClientException(statusCode, errorCode, statusCode == 429 || statusCode >= 500,
                message, exception);
    }

    @FunctionalInterface
    private interface ClientInvocation<T> {
        T execute();
    }
}
