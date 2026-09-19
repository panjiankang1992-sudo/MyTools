package com.yuyutian.mytools.asset.model;

import java.util.UUID;

/**
 * 任务执行领域写隔离凭据。
 *
 * @param taskInstanceId 任务实例标识
 * @param stepName 步骤名称
 * @param businessKey 领域幂等键
 * @param fencingToken 单调隔离令牌
 */
public record TaskExecutionFence(UUID taskInstanceId, String stepName, String businessKey, long fencingToken) {

    /**
     * 校验隔离凭据基本格式。
     *
     * @return 是否有效
     */
    public boolean valid() {
        return taskInstanceId != null && stepName != null && !stepName.isBlank() && stepName.length() <= 128
                && businessKey != null && !businessKey.isBlank() && businessKey.length() <= 255
                && fencingToken > 0;
    }
}
