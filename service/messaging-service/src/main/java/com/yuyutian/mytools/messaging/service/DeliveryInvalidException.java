package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ErrorCode;

/**
 * 投递请求业务字段无效异常。
 */
public class DeliveryInvalidException extends RuntimeException {

    /**
     * 创建投递请求无效异常。
     */
    public DeliveryInvalidException() {
        super(ErrorCode.DELIVERY_INVALID.code());
    }
}
