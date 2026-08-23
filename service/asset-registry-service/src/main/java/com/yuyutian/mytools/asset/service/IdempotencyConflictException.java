package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.ErrorCode;

/**
 * 幂等键绑定不同业务数据异常。
 */
public class IdempotencyConflictException extends RuntimeException {

    /**
     * 创建幂等冲突异常。
     */
    public IdempotencyConflictException() {
        super(ErrorCode.IDEMPOTENCY_CONFLICT.code());
    }
}
