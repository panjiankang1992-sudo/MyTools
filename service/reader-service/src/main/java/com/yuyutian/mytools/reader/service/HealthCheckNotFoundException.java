package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

import java.util.UUID;

/**
 * 书源健康检查不存在异常。
 */
public class HealthCheckNotFoundException extends RuntimeException {

    /**
     * 创建书源健康检查不存在异常。
     *
     * @param requestId 请求标识
     */
    public HealthCheckNotFoundException(UUID requestId) {
        super(ErrorCode.HEALTH_CHECK_NOT_FOUND.code() + ":" + requestId);
    }
}
