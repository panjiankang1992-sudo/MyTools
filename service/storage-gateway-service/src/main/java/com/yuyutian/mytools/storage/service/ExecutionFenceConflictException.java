package com.yuyutian.mytools.storage.service;

/**
 * 任务执行令牌过期或执行身份冲突异常。
 */
public class ExecutionFenceConflictException extends RuntimeException {

    /**
     * 创建执行隔离冲突异常。
     */
    public ExecutionFenceConflictException() {
        super(com.yuyutian.mytools.storage.model.ErrorCode.EXECUTION_FENCE_CONFLICT.code());
    }
}
