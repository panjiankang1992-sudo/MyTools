package com.yuyutian.mytools.messaging.provider;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateDeliveryRequest;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.service.DeliveryService;
import org.springframework.stereotype.Component;

/**
 * 邮件原会话回复适配器。
 */
@Component
public class EmailInboundReplyProvider implements InboundReplyProvider {

    private final DeliveryService deliveryService;

    /**
     * 创建邮件回复适配器。
     *
     * @param deliveryService 异步投递服务
     */
    public EmailInboundReplyProvider(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /** {@inheritDoc} */
    @Override
    public ChannelType channelType() {
        return ChannelType.EMAIL;
    }

    /** {@inheritDoc} */
    @Override
    public void reply(InboundMessageView message, String idempotencyKey, String body) {
        String subject = message.subject() == null || message.subject().isBlank()
                ? "Automation completed" : "Re: " + message.subject();
        deliveryService.create(new CreateDeliveryRequest(message.ownerId(), idempotencyKey,
                ChannelType.EMAIL, null, message.sender(), subject, body));
    }
}
