package com.yuyutian.mytools.drive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.client.CreateTaskRequest;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Drive 使用的任务调度客户端。
 */
public class DriveTaskSchedulerClient {
    private final com.yuyutian.mytools.task.client.TaskSchedulerClient client;

    /**
     * 创建客户端。
     *
     * @param restClient HTTP 客户端
     */
    public DriveTaskSchedulerClient(RestClient restClient) {
        this(restClient, "");
    }

    /**
     * 创建携带业务服务令牌的客户端。
     *
     * @param restClient HTTP 客户端
     * @param businessToken 业务服务令牌
     */
    public DriveTaskSchedulerClient(RestClient restClient, String businessToken) {
        this(new com.yuyutian.mytools.task.client.TaskSchedulerClient(restClient,
            new ObjectMapper().findAndRegisterModules(), businessToken));
    }

    /** 创建领域适配器。 @param client 公共 Scheduler 客户端 */
    public DriveTaskSchedulerClient(com.yuyutian.mytools.task.client.TaskSchedulerClient client) {
        this.client = client;
    }

    /**
     * 创建账户索引任务。
     *
     * @param operationId 操作标识
     * @param accountId 账户标识
     * @param idempotencyKey 幂等键
     * @return 任务标识
     */
    public UUID createIndexTask(UUID operationId, UUID accountId, String idempotencyKey) {
        return client.create(CreateTaskRequest.create("drive_index_account", "drive_index:" + idempotencyKey,
            "DRIVE_INDEX", operationId.toString(), 40, Map.of("accountId", accountId.toString()))).id();
    }

    /**
     * 查询任务状态。
     *
     * @param taskId 任务标识
     * @return 任务状态
     */
    public String getStatus(UUID taskId) {
        return client.get(taskId).status();
    }

    /**
     * 取消任务。
     *
     * @param taskId 任务标识
     */
    public void cancel(UUID taskId) {
        client.cancel(taskId);
    }
}
