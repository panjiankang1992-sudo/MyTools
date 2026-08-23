package com.yuyutian.mytools.task.executor.client;

import java.time.Instant;

/**
 * 执行租约续期结果。
 *
 * @param leaseUntil 租约截止时间
 * @param cancelRequested 是否请求取消
 */
public record ExecutionLease(Instant leaseUntil, boolean cancelRequested) {
}
