package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * 任务调度服务客户端。
 */
public class TaskSchedulerClient {

    private final RestClient restClient;

    /**
     * 创建任务调度客户端。
     */
    public TaskSchedulerClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 创建只包含投递标识的发送任务。
     */
    public UUID createDeliveryTask(UUID deliveryId, ChannelTask task) {
        Map<String, Object> request = Map.of(
                "taskName", task.taskName(),
                "idempotencyKey", "message_delivery:" + deliveryId + ":v1",
                "businessType", "MESSAGE_DELIVERY",
                "businessId", deliveryId.toString(),
                "priority", 80,
                "parameters", Map.of("deliveryId", deliveryId.toString()));
        JsonNode response = restClient.post().uri("/api/v1/task-instances")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Scheduler returned an invalid task response");
        }
        return UUID.fromString(response.path("id").asText());
    }

    /**
     * 创建只包含附件任务标识的处理任务。
     */
    public UUID createAttachmentDownloadTask(UUID jobId) {
        Map<String, Object> request = Map.of(
                "taskName", "message_download_attachment",
                "idempotencyKey", "message_attachment_download:" + jobId + ":v1",
                "businessType", "MESSAGE_ATTACHMENT",
                "businessId", jobId.toString(),
                "priority", 70,
                "parameters", Map.of("attachmentJobId", jobId.toString()));
        JsonNode response = restClient.post().uri("/api/v1/task-instances")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Scheduler returned an invalid task response");
        }
        return UUID.fromString(response.path("id").asText());
    }

    /**
     * 取消任务实例。
     *
     * @param taskId 任务标识
     */
    public void cancel(UUID taskId) {
        restClient.post().uri("/api/v1/task-instances/{id}/cancel", taskId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();
    }

    /**
     * 渠道到白名单任务定义的映射。
     */
    public record ChannelTask(String taskName) {
    }
}
