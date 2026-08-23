package com.yuyutian.mytools.task.executor.client;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务执行节点访问调度服务的协议接口。
 */
public interface SchedulerClient {

    /**
     * 注册执行节点。
     *
     * @param instanceId 启动实例标识
     * @return 注册信息
     * @throws IOException 调用失败
     */
    ExecutorNodeRegistration register(UUID instanceId) throws IOException;

    /**
     * 上报节点心跳。
     *
     * @param nodeId 节点标识
     * @param instanceId 启动实例标识
     * @param runningTasks 运行任务数
     * @throws IOException 调用失败
     */
    void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) throws IOException;

    /**
     * 领取任务。
     *
     * @param nodeId 节点标识
     * @param instanceId 启动实例标识
     * @return 可选任务
     * @throws IOException 调用失败
     */
    Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) throws IOException;

    /**
     * 续期执行租约。
     *
     * @param task 已领取任务
     * @return 租约状态
     * @throws IOException 调用失败
     */
    ExecutionLease heartbeatExecution(ClaimedTask task) throws IOException;

    /**
     * 上报步骤结果。
     *
     * @param task 已领取任务
     * @param step 步骤
     * @param attempt 尝试次数
     * @param status 状态
     * @param exitCode 退出码
     * @param result 结果
     * @param errorCode 错误码
     * @param errorMessage 错误摘要
     * @throws IOException 调用失败
     */
    void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                    Map<String, Object> result, String errorCode, String errorMessage) throws IOException;

    /**
     * 完成执行。
     *
     * @param task 已领取任务
     * @param status 最终状态
     * @throws IOException 调用失败
     */
    void complete(ClaimedTask task, String status) throws IOException;
}
