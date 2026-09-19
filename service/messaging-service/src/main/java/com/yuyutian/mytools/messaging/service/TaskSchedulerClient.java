package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.client.CreateTaskRequest;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * 任务调度服务客户端。
 */
public class TaskSchedulerClient {

    private final com.yuyutian.mytools.task.client.TaskSchedulerClient client;

    /**
     * 创建任务调度客户端。
     */
    public TaskSchedulerClient(RestClient restClient) {
        this(restClient, "");
    }

    /**
     * 创建携带业务服务令牌的任务调度客户端。
     *
     * @param restClient HTTP 客户端
     * @param businessToken 业务服务令牌
     */
    public TaskSchedulerClient(RestClient restClient, String businessToken) {
        this(new com.yuyutian.mytools.task.client.TaskSchedulerClient(restClient,
                new ObjectMapper().findAndRegisterModules(), businessToken));
    }

    /**
     * 创建基于公共 Scheduler 客户端的 Messaging 适配器。
     *
     * @param client 公共 Scheduler 客户端
     */
    public TaskSchedulerClient(com.yuyutian.mytools.task.client.TaskSchedulerClient client) {
        this.client = client;
    }

    /**
     * 创建只包含投递标识的发送任务。
     */
    public UUID createDeliveryTask(UUID deliveryId, ChannelTask task) {
        return client.create(CreateTaskRequest.create(task.taskName(),
                "message_delivery:" + deliveryId + ":v1", "MESSAGE_DELIVERY", deliveryId.toString(), 80,
                Map.of("deliveryId", deliveryId.toString()))).id();
    }

    /**
     * 创建只包含附件任务标识的处理任务。
     */
    public UUID createAttachmentDownloadTask(UUID jobId) {
        return client.create(CreateTaskRequest.create("message_download_attachment",
                "message_attachment_download:" + jobId + ":v1", "MESSAGE_ATTACHMENT", jobId.toString(), 70,
                Map.of("attachmentJobId", jobId.toString()))).id();
    }

    /**
     * 取消任务实例。
     *
     * @param taskId 任务标识
     */
    public void cancel(UUID taskId) {
        client.cancel(taskId);
    }

    /**
     * 查询任务实例状态。
     *
     * @param taskId 任务标识
     * @return 调度状态
     */
    public String status(UUID taskId) {
        String status = client.get(taskId).status();
        if (status.isBlank()) {
            throw new IllegalStateException("Scheduler returned an invalid task status");
        }
        return status;
    }

    /**
     * 渠道到白名单任务定义的映射。
     */
    public record ChannelTask(String taskName) {
    }
}
