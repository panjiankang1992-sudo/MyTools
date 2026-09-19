package com.yuyutian.mytools.task.executor.client;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 任务执行节点访问调度服务的协议接口。
 */
public interface SchedulerClient {
    /** 关闭宿主当前执行授权，旧客户端及不含正文能力的任务无需操作。 */
    default void releaseWorkloadAuthorization(UUID executionId) {
        // 兼容不持有工作负载授权的历史客户端。
    }


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
     * 更新节点调度状态。
     *
     * @param nodeId 节点标识
     * @param expectedInstanceId 预期的启动实例标识
     * @param status 目标状态
     * @param reason 状态原因
     * @throws IOException 调用失败
     */
    default void updateNodeStatus(UUID nodeId, UUID expectedInstanceId, String status, String reason)
            throws IOException {
        // 兼容只实现执行协议的测试客户端，生产 HTTP 客户端必须覆盖该方法。
    }

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
     * 按任务层级领取任务。
     *
     * @param nodeId 节点标识
     * @param instanceId 启动实例标识
     * @param childTaskOnly 是否只领取具有父任务的子任务
     * @return 可选任务
     * @throws IOException 调用失败
     */
    default Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId, boolean childTaskOnly) throws IOException {
        // 旧客户端不知道如何在服务端过滤子任务，保留槽请求必须安全返回空，不能误领根任务。
        return childTaskOnly ? Optional.empty() : claim(nodeId, instanceId);
    }

    /**
     * 只领取根任务。
     *
     * @param nodeId 节点标识
     * @param instanceId 启动实例标识
     * @return 可选根任务
     * @throws IOException 调用失败
     */
    default Optional<ClaimedTask> claimRootTask(UUID nodeId, UUID instanceId) throws IOException {
        // 兼容旧测试客户端；工作器仍会校验返回任务确实是根任务。
        return claim(nodeId, instanceId);
    }

    /**
     * 只领取指定父任务的直接子任务。
     *
     * @param nodeId 节点标识
     * @param instanceId 启动实例标识
     * @param parentTaskInstanceIds 父任务标识集合
     * @return 可选直接子任务
     * @throws IOException 调用失败
     */
    default Optional<ClaimedTask> claimDirectChildTask(UUID nodeId, UUID instanceId,
                                                       Set<UUID> parentTaskInstanceIds) throws IOException {
        // 旧客户端无法保证直接父任务过滤，宁可保留槽空闲也不能误领同层任务。
        return Optional.empty();
    }

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
     * 上报带日志索引的步骤结果。
     *
     * @param task 已领取任务
     * @param step 步骤
     * @param attempt 尝试次数
     * @param status 状态
     * @param exitCode 退出码
     * @param result 结果
     * @param errorCode 错误码
     * @param errorMessage 错误摘要
     * @param logIndex 日志分段索引
     * @throws IOException 调用失败
     */
    default void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                            Map<String, Object> result, String errorCode, String errorMessage,
                            Map<String, Object> logIndex) throws IOException {
        reportStep(task, step, attempt, status, exitCode, result, errorCode, errorMessage);
    }

    /**
     * 完成执行。
     *
     * @param task 已领取任务
     * @param status 最终状态
     * @throws IOException 调用失败
     */
    void complete(ClaimedTask task, String status) throws IOException;

    /**
     * 完成执行并上报补偿结果。
     *
     * @param task 已领取任务
     * @param completion 执行终态及补偿结果
     * @throws IOException 调用失败
     */
    default void complete(ClaimedTask task, ExecutionCompletion completion) throws IOException {
        complete(task, completion.status());
    }
}
