package com.yuyutian.mytools.task.scheduler.model;

/**
 * 任务实例状态。
 */
public enum TaskStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_CHILDREN,
    CANCELLING,
    CANCELLED,
    SUCCEEDED,
    FAILED,
    TIMED_OUT
}
