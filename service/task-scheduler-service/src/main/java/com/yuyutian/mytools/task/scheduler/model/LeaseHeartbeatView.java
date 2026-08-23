package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;

/**
 * 执行租约状态。
 *
 * @param leaseUntil 租约截止时间
 * @param cancelRequested 是否请求取消
 */
public record LeaseHeartbeatView(Instant leaseUntil, boolean cancelRequested) {
}
