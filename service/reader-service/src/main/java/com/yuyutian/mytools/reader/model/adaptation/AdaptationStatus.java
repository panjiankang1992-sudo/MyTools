package com.yuyutian.mytools.reader.model.adaptation;

/** 改编业务版本的状态，失败原因由独立错误码表达。 */
public enum AdaptationStatus {
    PENDING_DISPATCH,
    QUEUED,
    CONTEXT_FREEZING,
    ANALYZING,
    GENERATING,
    VALIDATING,
    REPAIRING,
    PERSISTING,
    COMPLETED,
    CANCEL_REQUESTED,
    CANCELLED,
    FAILED;

    /** 判断当前状态是否已经不可回退。 */
    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
