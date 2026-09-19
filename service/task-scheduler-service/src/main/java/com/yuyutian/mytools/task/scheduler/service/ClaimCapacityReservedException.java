package com.yuyutian.mytools.task.scheduler.service;

/**
 * 表示当前领取范围内有编排任务正在等待预留容量。
 */
public class ClaimCapacityReservedException extends RuntimeException {

    /**
     * 创建容量预留信号。
     */
    public ClaimCapacityReservedException() {
        super("Task claim capacity is reserved for an orchestrator");
    }
}
