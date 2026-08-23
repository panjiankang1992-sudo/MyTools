package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

import java.util.UUID;

/**
 * 书源发现请求不存在异常。
 */
public class DiscoveryNotFoundException extends RuntimeException {

    /**
     * 创建书源发现请求不存在异常。
     *
     * @param requestId 请求标识
     */
    public DiscoveryNotFoundException(UUID requestId) {
        super(ErrorCode.DISCOVERY_NOT_FOUND.code() + ":" + requestId);
    }
}
