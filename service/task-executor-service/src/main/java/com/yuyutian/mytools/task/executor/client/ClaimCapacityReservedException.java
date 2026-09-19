package com.yuyutian.mytools.task.executor.client;

import java.io.IOException;

/**
 * 表示调度端要求保留当前槽位以推进更深层的编排任务。
 */
public class ClaimCapacityReservedException extends IOException {

    /**
     * 创建容量预留信号。
     */
    public ClaimCapacityReservedException() {
        super("Scheduler reserved claim capacity for an orchestrator");
    }
}
