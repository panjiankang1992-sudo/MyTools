package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ErrorCode;

import java.util.UUID;

/**
 * 标准入站消息不存在异常。
 */
public class InboundMessageNotFoundException extends RuntimeException {

    /**
     * 创建入站消息不存在异常。
     */
    public InboundMessageNotFoundException(UUID id) {
        super(ErrorCode.INBOUND_NOT_FOUND.code() + ":" + id);
    }
}
