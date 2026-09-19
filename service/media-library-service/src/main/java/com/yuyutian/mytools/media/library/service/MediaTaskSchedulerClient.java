package com.yuyutian.mytools.media.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.client.CreateTaskRequest;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Media Library 任务调度客户端。
 */
public class MediaTaskSchedulerClient {

    private final com.yuyutian.mytools.task.client.TaskSchedulerClient client;

    /** 创建客户端。 @param client HTTP 客户端 */
    public MediaTaskSchedulerClient(RestClient client) {
        this(client, "");
    }

    /** 创建携带业务服务令牌的客户端。 @param client HTTP 客户端 @param businessToken 业务服务令牌 */
    public MediaTaskSchedulerClient(RestClient client, String businessToken) {
        this(new com.yuyutian.mytools.task.client.TaskSchedulerClient(client,
                new ObjectMapper().findAndRegisterModules(), businessToken));
    }

    /** 创建领域适配器。 @param client 公共 Scheduler 客户端 */
    public MediaTaskSchedulerClient(com.yuyutian.mytools.task.client.TaskSchedulerClient client) {
        this.client = client;
    }

    /** 创建目录扫描任务。 @param operationId 操作标识 @param ownerId 所有者标识 @param idempotencyKey 幂等键 @param parameters 参数 @return 任务标识 */
    public UUID createScan(UUID operationId, long ownerId, String idempotencyKey,
                           Map<String, Object> parameters) {
        return client.create(CreateTaskRequest.create("media_scan_directory", idempotencyKey, "MEDIA_SCAN",
                operationId.toString(), 40, parameters)).id();
    }

    /** 创建媒体分析任务。 @param operationId 操作标识 @param idempotencyKey 幂等键 @param parameters 参数 @return 任务标识 */
    public UUID createAnalysis(UUID operationId, String idempotencyKey, Map<String, Object> parameters) {
        return client.create(CreateTaskRequest.create("media_analyze_video", idempotencyKey, "MEDIA_ANALYSIS",
                operationId.toString(), 50, parameters)).id();
    }

    /** 查询任务状态。 @param taskId 任务标识 @return 状态 */
    public String status(UUID taskId) {
        return client.get(taskId).status();
    }

    /** 取消任务。 @param taskId 任务标识 */
    public void cancel(UUID taskId) {
        client.cancel(taskId);
    }
}
