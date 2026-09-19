package com.yuyutian.mytools.messaging.service;

/**
 * 渠道提供方已持有同一幂等回复，要求调用方稍后重试时抛出的异常。
 */
public class InboundReplyDeferredException extends RuntimeException {

    private static final int MINIMUM_RETRY_AFTER_SECONDS = 1;
    private static final int MAXIMUM_RETRY_AFTER_SECONDS = 60;

    private final int retryAfterSeconds;

    /**
     * 创建渠道回复延迟异常。
     *
     * @param retryAfterSeconds 建议重试等待秒数
     * @param cause 渠道提供方返回的原始异常
     */
    public InboundReplyDeferredException(long retryAfterSeconds, Throwable cause) {
        super(cause);
        // 下游响应头仅能影响一个很小的退避窗口，避免异常值长期冻结完成通知。
        this.retryAfterSeconds = (int) Math.max(MINIMUM_RETRY_AFTER_SECONDS,
                Math.min(MAXIMUM_RETRY_AFTER_SECONDS, retryAfterSeconds));
    }

    /**
     * 返回经过安全边界约束的重试等待秒数。
     *
     * @return 重试等待秒数
     */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
