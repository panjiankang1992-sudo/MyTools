package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateInboundReplyRequest;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.model.InboundReplyView;
import com.yuyutian.mytools.messaging.provider.InboundReplyProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 根据原入站消息保存的路由信息分发回复。
 */
@Service
public class InboundReplyService {

    private final DeliveryService deliveryService;
    private final Map<ChannelType, InboundReplyProvider> providers = new EnumMap<>(ChannelType.class);

    /**
     * 创建统一入站回复服务。
     *
     * @param deliveryService 标准消息服务
     * @param providerList 渠道回复适配器
     */
    public InboundReplyService(DeliveryService deliveryService, List<InboundReplyProvider> providerList) {
        this.deliveryService = deliveryService;
        providerList.forEach(provider -> providers.put(provider.channelType(), provider));
    }

    /**
     * 按原消息渠道受理回复。
     *
     * @param messageId 入站消息标识
     * @param request 回复请求
     * @return 受理结果
     */
    public InboundReplyView reply(UUID messageId, CreateInboundReplyRequest request) {
        InboundMessageView message = deliveryService.inbound(messageId);
        InboundReplyProvider provider = providers.get(message.channelType());
        if (provider == null) {
            throw new ProviderNotConfiguredException(message.channelType());
        }
        try {
            provider.reply(message, request.idempotencyKey(), request.body());
        } catch (HttpClientErrorException exception) {
            // 渠道侧的请求无效或目标不存在不会通过重试恢复，转换为稳定的永久失败。
            if (exception.getStatusCode().value() == 400 || exception.getStatusCode().value() == 404) {
                throw new InboundReplyRejectedException(exception);
            }
            throw exception;
        }
        return new InboundReplyView(messageId, message.channelType(), "ACCEPTED");
    }
}
