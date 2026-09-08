package com.yuyutian.mytools.task.client;

/**
 * Scheduler 公共客户端异常。
 */
public class TaskSchedulerClientException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;
    private final boolean retryable;

    /**
     * 创建客户端异常。
     *
     * @param statusCode HTTP 状态码，网络错误为零
     * @param errorCode 结构化错误码
     * @param retryable 是否允许重试
     * @param message 安全错误信息
     * @param cause 原始异常
     */
    public TaskSchedulerClientException(int statusCode, String errorCode, boolean retryable,
                                        String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /** @return HTTP 状态码 */
    public int statusCode() {
        return statusCode;
    }

    /** @return 结构化错误码 */
    public String errorCode() {
        return errorCode;
    }

    /** @return 是否允许重试 */
    public boolean retryable() {
        return retryable;
    }
}
