package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;

/** 受守卫条件约束的业务状态转换，数据库事务另行校验版本与执行租约。 */
public final class ChapterAdaptationStateMachine {

    private ChapterAdaptationStateMachine() {
    }

    /** 记录状态转换前已从数据库复核的条件。 */
    public record Guards(boolean contextSealed, boolean constraintsSealed, boolean candidateStored,
                         boolean validationPassed, boolean repairable, boolean selectionStored,
                         int repairCount, int unsettledCalls) {
        /** 拒绝不存在于业务状态中的计数。 */
        public Guards {
            // 修复次数和未决调用数必须来自持久状态，不能接受负数。
            if (repairCount < 0 || repairCount > 1 || unsettledCalls < 0) {
                throw new ChapterAdaptationException(ErrorCode.READER_STATE_CONFLICT);
            }
        }
    }

    /** 判断转换是否满足唯一状态图和对应持久化前置条件。 */
    public static boolean canTransition(AdaptationStatus from, AdaptationStatus to, Guards guards) {
        // 终态和相同状态不能再次推进，幂等重放由调用方单独收敛。
        if (from == null || to == null || guards == null || from.terminal() || from == to) {
            return false;
        }
        // 取消请求保持到全部已发出调用收敛，不能把取消改成普通失败。
        if (from == AdaptationStatus.CANCEL_REQUESTED) {
            return to == AdaptationStatus.CANCELLED && guards.unsettledCalls() == 0;
        }
        if (to == AdaptationStatus.CANCEL_REQUESTED) {
            return true;
        }
        // 失败收敛必须先把未决调用标记为已完成或结果未知。
        if (to == AdaptationStatus.FAILED) {
            return guards.unsettledCalls() == 0;
        }
        return switch (from) {
            case PENDING_DISPATCH -> to == AdaptationStatus.QUEUED;
            case QUEUED -> to == AdaptationStatus.CONTEXT_FREEZING
                    || (to == AdaptationStatus.ANALYZING && guards.contextSealed());
            case CONTEXT_FREEZING -> to == AdaptationStatus.ANALYZING && guards.contextSealed();
            case ANALYZING -> to == AdaptationStatus.GENERATING && guards.contextSealed()
                    && guards.constraintsSealed() && guards.unsettledCalls() == 0;
            case GENERATING, REPAIRING -> to == AdaptationStatus.VALIDATING && guards.candidateStored()
                    && guards.unsettledCalls() == 0;
            case VALIDATING -> guards.unsettledCalls() == 0 && guards.candidateStored()
                    && ((to == AdaptationStatus.REPAIRING && guards.repairable()
                            && !guards.validationPassed() && guards.repairCount() == 0)
                        || (to == AdaptationStatus.PERSISTING && guards.validationPassed()));
            case PERSISTING -> to == AdaptationStatus.COMPLETED && guards.validationPassed()
                    && guards.selectionStored() && guards.unsettledCalls() == 0;
            default -> false;
        };
    }

    /** 校验状态转换，不将外部错误或正文放入异常。 */
    public static void requireTransition(AdaptationStatus from, AdaptationStatus to, Guards guards) {
        if (!canTransition(from, to, guards)) {
            // 调用方不能绕过守卫直接把候选发布为成功版本。
            throw new ChapterAdaptationException(ErrorCode.READER_STATE_CONFLICT);
        }
    }
}
