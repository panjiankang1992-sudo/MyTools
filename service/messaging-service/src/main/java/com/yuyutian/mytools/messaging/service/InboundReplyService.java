package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateInboundReplyRequest;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.model.InboundReplyView;
import com.yuyutian.mytools.messaging.provider.InboundReplyProvider;
import org.springframework.http.HttpHeaders;
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

    private static final int DEFAULT_RETRY_AFTER_SECONDS = 1;

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
            int statusCode = exception.getStatusCode().value();
            if (statusCode == 425) {
                // QQ 出站 WAL 尚在租约或退避窗口时必须通知 Automation 延迟，不能误报已受理。
                throw new InboundReplyDeferredException(retryAfterSeconds(exception), exception);
            }
            // 渠道侧的请求无效或目标不存在不会通过重试恢复，转换为稳定的永久失败。
            if (statusCode == 400 || statusCode == 404) {
                throw new InboundReplyRejectedException(exception);
            }
            if (statusCode == 409 || statusCode == 422) {
                // 幂等载荷冲突和已耗尽记录需保留原状态，供 Automation 立即转入死信。
                throw new InboundReplyProviderFailureException(statusCode, exception);
            }
            throw exception;
        }
        return new InboundReplyView(messageId, message.channelType(), "ACCEPTED");
    }

    private long retryAfterSeconds(HttpClientErrorException exception) {
        HttpHeaders headers = exception.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            // 内部 Connector 协议只允许 delta-seconds；损坏值采用最短安全退避。
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }
}
