package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 远端移动持久化状态机。
 *
 * @param operationId 操作标识
 * @param phase 当前阶段
 * @param remoteJobId 当前 rclone 任务标识
 * @param desiredTerminalStatus 中止后的期望终态
 * @param failureCode 原始失败码
 * @param recoveryAction 恢复动作
 * @param recoveryRequired 是否需要恢复处理
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record StorageMoveState(UUID operationId, String phase, Long remoteJobId, String desiredTerminalStatus,
                               String failureCode, String recoveryAction, boolean recoveryRequired,
                               Instant createdAt, Instant updatedAt) {
}
