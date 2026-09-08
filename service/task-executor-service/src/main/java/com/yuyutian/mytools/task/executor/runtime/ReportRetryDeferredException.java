package com.yuyutian.mytools.task.executor.runtime;

import java.io.IOException;
import java.time.Instant;

/**
 * 表示持久上报已按 WAL 中的退避时间延后。
 */
public class ReportRetryDeferredException extends IOException {

    private final Instant retryAt;

    /**
     * 创建尚未到期的持久上报退避异常。
     *
     * @param retryAt 下次允许尝试的绝对时间
     */
    public ReportRetryDeferredException(Instant retryAt) {
        super("Execution report retry is deferred until " + retryAt);
        this.retryAt = retryAt;
    }

    /**
     * 创建由本次投递失败触发的持久上报退避异常。
     *
     * @param retryAt 下次允许尝试的绝对时间
     * @param cause 原始投递异常
     */
    public ReportRetryDeferredException(Instant retryAt, IOException cause) {
        super("Execution report retry is deferred until " + retryAt, cause);
        this.retryAt = retryAt;
    }

    /**
     * 返回下次允许尝试的绝对时间。
     *
     * @return 下次尝试时间
     */
    public Instant retryAt() {
        return retryAt;
    }
}
