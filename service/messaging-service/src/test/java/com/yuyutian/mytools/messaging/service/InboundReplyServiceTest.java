package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateInboundReplyRequest;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.provider.InboundReplyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InboundReplyServiceTest {

    @Test
    void shouldConvertPermanentProviderRejection() {
        DeliveryService deliveryService = mock(DeliveryService.class);
        UUID messageId = UUID.randomUUID();
        when(deliveryService.inbound(messageId)).thenReturn(new InboundMessageView(messageId, 1L, ChannelType.QQ,
                "qq:private:1", "qq:private:2", "2", null, "message", Instant.now(), Instant.now(), List.of()));
        InboundReplyProvider provider = new InboundReplyProvider() {
            @Override
            public ChannelType channelType() {
                return ChannelType.QQ;
            }

            @Override
            public void reply(InboundMessageView message, String idempotencyKey, String body) {
                // 模拟渠道侧认为原消息已失效并永久拒绝回复。
                throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
            }
        };
        InboundReplyService service = new InboundReplyService(deliveryService, List.of(provider));

        assertThatThrownBy(() -> service.reply(messageId, new CreateInboundReplyRequest("reply-1", "done")))
                .isInstanceOf(InboundReplyRejectedException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void shouldPreserveTransientProviderFailure() {
        DeliveryService deliveryService = mock(DeliveryService.class);
        UUID messageId = UUID.randomUUID();
        when(deliveryService.inbound(messageId)).thenReturn(new InboundMessageView(messageId, 1L, ChannelType.TELEGRAM,
                "telegram:message:1", "telegram:chat:2", "2", null, "message", Instant.now(), Instant.now(), List.of()));
        InboundReplyProvider provider = new InboundReplyProvider() {
            @Override
            public ChannelType channelType() {
                return ChannelType.TELEGRAM;
            }

            @Override
            public void reply(InboundMessageView message, String idempotencyKey, String body) {
                // 限流等可恢复错误必须保留给上层重试。
                throw new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
            }
        };
        InboundReplyService service = new InboundReplyService(deliveryService, List.of(provider));

        assertThatThrownBy(() -> service.reply(messageId, new CreateInboundReplyRequest("reply-2", "done")))
                .isInstanceOf(HttpClientErrorException.class)
                .isNotInstanceOf(InboundReplyRejectedException.class);
    }
}
