package com.yuyutian.mytools.task.scheduler.model;

/**
 * 执行节点状态。
 */
public enum NodeStatus {
    ONLINE,
    BUSY,
    DRAINING,
    UNHEALTHY,
    OFFLINE,
    DISABLED
}
