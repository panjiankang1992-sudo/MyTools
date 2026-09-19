package com.yuyutian.mytools.task.client;

/**
 * 任务调度网关结构化异常。
 */
public class TaskSchedulerGatewayException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;
    private final boolean retryable;

    /**
     * 创建任务调度网关异常。
     *
     * @param statusCode HTTP 状态码
     * @param errorCode Scheduler 错误码
     * @param retryable 是否可重试
     * @param message 错误摘要
     * @param cause 原始异常
     */
    public TaskSchedulerGatewayException(int statusCode, String errorCode, boolean retryable,
                                         String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /** 返回 HTTP 状态码。 @return HTTP 状态码 */
    public int statusCode() {
        return statusCode;
    }

    /** 返回 Scheduler 错误码。 @return 错误码 */
    public String errorCode() {
        return errorCode;
    }

    /** 返回调用是否可重试。 @return 是否可重试 */
    public boolean retryable() {
        return retryable;
    }
}
