package com.yuyutian.mytools.task.scheduler.model;

/**
 * 任务步骤种类。
 */
public enum StepKind {
    NORMAL,
    ON_TIMEOUT,
    ON_FAILURE,
    ON_CANCEL
}
