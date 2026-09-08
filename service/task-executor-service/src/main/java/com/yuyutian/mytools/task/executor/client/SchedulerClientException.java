package com.yuyutian.mytools.task.executor.client;

import java.io.IOException;

/**
 * 可机器区分的调度服务协议异常。
 */
public class SchedulerClientException extends IOException {

    private final int statusCode;
    private final String errorCode;
    private final boolean retryable;

    /**
     * 创建调度服务协议异常。
     *
     * @param statusCode HTTP 状态码
     * @param errorCode 服务端稳定错误码
     * @param retryable 是否允许自动重试
     */
    public SchedulerClientException(int statusCode, String errorCode, boolean retryable) {
        super("Scheduler request failed with HTTP " + statusCode + " and code " + errorCode);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 返回 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 稳定错误码
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 返回是否允许自动重试。
     *
     * @return 是否允许自动重试
     */
    public boolean retryable() {
        return retryable;
    }
}
