package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.ErrorCode;

/**
 * 消息渠道适配器未配置异常。
 */
public class ProviderNotConfiguredException extends RuntimeException {

    /**
     * 创建渠道适配器未配置异常。
     */
    public ProviderNotConfiguredException(ChannelType type) {
        super(ErrorCode.PROVIDER_NOT_CONFIGURED.code() + ":" + type.name());
    }
}
