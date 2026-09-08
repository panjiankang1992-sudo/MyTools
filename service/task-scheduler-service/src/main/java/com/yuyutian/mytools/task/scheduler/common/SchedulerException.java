package com.yuyutian.mytools.task.scheduler.common;

import org.springframework.http.HttpStatus;

/**
 * 调度服务结构化业务异常。
 */
public class SchedulerException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    /**
     * 创建结构化业务异常。
     *
     * @param errorCode 错误码
     * @param status HTTP 状态
     * @param message 错误摘要
     */
    public SchedulerException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    /**
     * 返回错误码。
     *
     * @return 错误码
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 返回 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus status() {
        return status;
    }
}
