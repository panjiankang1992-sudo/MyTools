package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ErrorCode;

/**
 * 投递状态不允许当前操作异常。
 */
public class DeliveryStateInvalidException extends RuntimeException {

    /**
     * 创建投递状态异常。
     */
    public DeliveryStateInvalidException() {
        super(ErrorCode.DELIVERY_STATE_INVALID.code());
    }
}
