package com.yuyutian.mytools.reader.model.adaptation;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 派发控制面的状态视图，只携带不透明身份、租约与有限调度状态。 */
public final class AdaptationDispatchModels {
    private AdaptationDispatchModels() {
    }

    /** 持久派发领取的动作不等同于拥有模型执行权限。 */
    public enum Action {
        SUBMIT, OBSERVE, CANCEL
    }

    /** 数据库短租约用于网络调用后的等值 CAS，不作为 Executor fence。 */
    public record Claim(UUID adaptationId, long ownerId, UUID taskInstanceId, long epoch, String worker,
                         Instant claimedUntil, int submissionAttempt, Action action) {
    }

    /** Scheduler 必须明确确认取消屏障；查询任务不存在本身不是取消证明。 */
    public record SchedulerView(UUID adaptationId, UUID taskInstanceId, boolean cancellationRecorded, String taskStatus) {
        /** 仅持久取消屏障与任务终结同时满足，或屏障确认任务尚未创建，才允许收敛。 */
        public boolean cancellationSettled() {
            return cancellationRecorded && (taskInstanceId == null || terminal());
        }

        /** 返回 Scheduler 的固定终态集合。 */
        public boolean terminal() {
            return taskStatus != null && Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(taskStatus);
        }
    }
}
