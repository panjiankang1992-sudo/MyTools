package com.yuyutian.mytools.reader.service.adaptation;

/** 不保留响应体、内部凭据或网络异常链的调度错误。 */
public final class AdaptationSchedulerException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean retryable;

    /** 按有无安全重试机会分类，调度幂等键始终保持不变。 */
    public AdaptationSchedulerException(boolean retryable) {
        super("Adaptation scheduler operation failed");
        this.retryable = retryable;
    }

    /** 返回是否允许在同一业务键下重试提交。 */
    public boolean retryable() {
        return retryable;
    }
}
