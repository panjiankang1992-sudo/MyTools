package com.yuyutian.mytools.messaging.provider;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.InboundMessageView;

/**
 * 原入站会话回复渠道适配器。
 */
public interface InboundReplyProvider {

    /**
     * 返回适配器支持的渠道。
     *
     * @return 渠道类型
     */
    ChannelType channelType();

    /**
     * 幂等受理对原入站消息的回复。
     *
     * @param message 原入站消息
     * @param idempotencyKey 幂等键
     * @param body 回复正文
     */
    void reply(InboundMessageView message, String idempotencyKey, String body);
}
