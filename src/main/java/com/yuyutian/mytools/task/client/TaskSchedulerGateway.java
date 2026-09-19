package com.yuyutian.mytools.task.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MyTools 调用任务调度服务的统一网关。
 */
@Component
public class TaskSchedulerGateway {

    static final String BUSINESS_TOKEN_HEADER = "X-Task-Business-Token";
    static final String SERVICE_ID_HEADER = "X-Task-Service-Id";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String schedulerUrl;
    private final String businessToken;

    /**
     * 创建任务调度网关。
     *
     * @param restTemplate HTTP 客户端
     * @param objectMapper JSON 映射器
     * @param schedulerUrl 任务调度服务地址
     * @param businessToken 业务接口令牌
     */
    @Autowired
    public TaskSchedulerGateway(RestTemplate restTemplate, ObjectMapper objectMapper,
                                @Value("${migration.tasks.scheduler-url:http://127.0.0.1:23210}")
                                String schedulerUrl,
                                @Value("${migration.tasks.internal-token:}") String businessToken) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.schedulerUrl = schedulerUrl;
        this.businessToken = businessToken;
    }

    /**
     * 创建兼容测试和旧手工装配代码的任务调度网关。
     *
     * @param restTemplate HTTP 客户端
     * @param schedulerUrl 任务调度服务地址
     */
    public TaskSchedulerGateway(RestTemplate restTemplate, String schedulerUrl) {
        this(restTemplate, new ObjectMapper().findAndRegisterModules(), schedulerUrl, "");
    }

    /**
     * 幂等创建一个任务实例。
     *
     * @param taskName 任务定义名称
     * @param idempotencyKey 幂等键
     * @param businessType 业务类型
     * @param businessId 业务标识
     * @param priority 优先级
     * @param parameters 参数
     * @return 任务实例标识
     */
    public UUID create(String taskName, String idempotencyKey, String businessType, String businessId,
                       int priority, Map<String, Object> parameters) {
        return create(taskName, idempotencyKey, businessType, businessId, priority, parameters, Map.of());
    }

    /**
     * 幂等创建带节点标签约束的任务实例。
     *
     * @param taskName 任务定义名称
     * @param idempotencyKey 幂等键
     * @param businessType 业务类型
     * @param businessId 业务标识
     * @param priority 优先级
     * @param parameters 参数
     * @param requiredNodeLabels 节点标签约束
     * @return 任务实例标识
     */
    public UUID create(String taskName, String idempotencyKey, String businessType, String businessId,
                       int priority, Map<String, Object> parameters,
                       Map<String, Object> requiredNodeLabels) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskName", taskName);
        request.put("idempotencyKey", idempotencyKey);
        request.put("businessType", businessType);
        request.put("businessId", businessId);
        request.put("parentTaskInstanceId", null);
        request.put("priority", priority);
        request.put("parameters", parameters);
        request.put("requiredNodeLabels", requiredNodeLabels);
        return exchange("/api/v1/task-instances", HttpMethod.POST,
                request, TaskInstanceSnapshot.class).id();
    }

    /**
     * 查询任务实例快照。
     *
     * @param taskInstanceId 任务实例标识
     * @return 任务实例快照
     */
    public TaskInstanceSnapshot get(UUID taskInstanceId) {
        return exchange("/api/v1/task-instances/" + taskInstanceId, HttpMethod.GET,
                null, TaskInstanceSnapshot.class);
    }

    /**
     * 请求取消任务实例。
     *
     * @param taskInstanceId 任务实例标识
     * @return 取消后的任务实例快照
     */
    public TaskInstanceSnapshot cancel(UUID taskInstanceId) {
        return exchange("/api/v1/task-instances/" + taskInstanceId + "/cancel", HttpMethod.POST,
                Map.of(), TaskInstanceSnapshot.class);
    }

    /**
     * 查询任务实例的全部步骤执行结果。
     *
     * @param taskInstanceId 任务实例标识
     * @return 任务执行结果
     */
    public TaskExecutionResultSnapshot getResults(UUID taskInstanceId) {
        return exchange("/api/v1/task-instances/" + taskInstanceId + "/results", HttpMethod.GET,
                null, TaskExecutionResultSnapshot.class);
    }

    private <T> T exchange(String path, HttpMethod method, Object request, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (businessToken != null && !businessToken.isBlank()) {
            headers.set(BUSINESS_TOKEN_HEADER, businessToken);
        }
        headers.set(SERVICE_ID_HEADER, "mytools-service");
        try {
            ResponseEntity<T> response = restTemplate.exchange(normalizedSchedulerUrl() + path, method,
                    new HttpEntity<>(request, headers), responseType);
            if (response.getBody() == null) {
                throw new IllegalStateException("Task Scheduler response body is empty");
            }
            return response.getBody();
        } catch (HttpStatusCodeException exception) {
            throw structuredException(exception);
        } catch (RestClientException exception) {
            throw new TaskSchedulerGatewayException(0, "NETWORK_ERROR", true,
                    "Task Scheduler request failed", exception);
        }
    }

    private TaskSchedulerGatewayException structuredException(HttpStatusCodeException exception) {
        String errorCode = "HTTP_" + exception.getStatusCode().value();
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
            // 非 JSON 错误响应保留 HTTP 派生错误码。
        }
        int statusCode = exception.getStatusCode().value();
        boolean retryable = statusCode == 429 || statusCode >= 500;
        return new TaskSchedulerGatewayException(statusCode, errorCode, retryable, message, exception);
    }

    private String normalizedSchedulerUrl() {
        return schedulerUrl.endsWith("/") ? schedulerUrl.substring(0, schedulerUrl.length() - 1) : schedulerUrl;
    }
}
