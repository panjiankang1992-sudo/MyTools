package com.yuyutian.mytools.messaging.provider;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.DeliveryRecord;

/**
 * 原子消息渠道发送适配器。
 */
public interface DeliveryProvider {

    /**
     * 返回支持的渠道。
     */
    ChannelType channelType();

    /**
     * 执行一次原子投递。
     *
     * @return 渠道消息标识
     */
    String deliver(DeliveryRecord delivery);
}
