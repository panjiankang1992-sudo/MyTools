package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ErrorCode;

import java.util.UUID;

/**
 * 投递请求不存在异常。
 */
public class DeliveryNotFoundException extends RuntimeException {

    /**
     * 创建投递请求不存在异常。
     */
    public DeliveryNotFoundException(UUID id) {
        super(ErrorCode.DELIVERY_NOT_FOUND.code() + ":" + id);
    }
}
