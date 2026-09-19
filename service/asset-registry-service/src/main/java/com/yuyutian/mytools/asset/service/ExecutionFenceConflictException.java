package com.yuyutian.mytools.asset.service;

/**
 * 旧执行令牌或相同令牌携带冲突身份。
 */
public class ExecutionFenceConflictException extends RuntimeException {

    /**
     * 创建执行隔离冲突异常。
     */
    public ExecutionFenceConflictException() {
        super("Asset execution fence is stale or conflicts");
    }
}
