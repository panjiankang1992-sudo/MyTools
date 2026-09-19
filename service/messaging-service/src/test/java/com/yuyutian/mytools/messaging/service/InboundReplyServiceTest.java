package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateInboundReplyRequest;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.provider.InboundReplyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InboundReplyServiceTest {

    @Test
    void shouldConvertPermanentProviderRejection() {
        DeliveryService deliveryService = mock(DeliveryService.class);
        UUID messageId = UUID.randomUUID();
        when(deliveryService.inbound(messageId)).thenReturn(new InboundMessageView(messageId, 1L, ChannelType.QQ,
                "qq:private:1", "qq:private:2", "2", null, "message", Instant.now(), Instant.now(), List.of(),
                false));
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
                "telegram:message:1", "telegram:chat:2", "2", null, "message", Instant.now(), Instant.now(),
                List.of(), false));
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

    @Test
    void shouldConvertTooEarlyToBoundedDeferredFailure() {
        DeliveryService deliveryService = mock(DeliveryService.class);
        UUID messageId = UUID.randomUUID();
        when(deliveryService.inbound(messageId)).thenReturn(new InboundMessageView(messageId, 1L, ChannelType.QQ,
                "qq:private:1", "qq:private:2", "2", null, "message", Instant.now(), Instant.now(), List.of(),
                false));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "300");
        HttpClientErrorException tooEarly = HttpClientErrorException.create(HttpStatusCode.valueOf(425), "Too Early",
                headers, new byte[0], StandardCharsets.UTF_8);
        InboundReplyProvider provider = providerThrowing(ChannelType.QQ, tooEarly);
        InboundReplyService service = new InboundReplyService(deliveryService, List.of(provider));

        Throwable thrown = catchThrowable(
                () -> service.reply(messageId, new CreateInboundReplyRequest("reply-3", "done")));

        assertThat(thrown).isInstanceOf(InboundReplyDeferredException.class).hasCause(tooEarly);
        assertThat(((InboundReplyDeferredException) thrown).retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void shouldUseMinimumDelayForInvalidRetryAfter() {
        DeliveryService deliveryService = mock(DeliveryService.class);
        UUID messageId = UUID.randomUUID();
        when(deliveryService.inbound(messageId)).thenReturn(new InboundMessageView(messageId, 1L, ChannelType.QQ,
                "qq:private:1", "qq:private:2", "2", null, "message", Instant.now(), Instant.now(), List.of(),
                false));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "invalid");
        HttpClientErrorException tooEarly = HttpClientErrorException.create(HttpStatusCode.valueOf(425), "Too Early",
                headers, new byte[0], StandardCharsets.UTF_8);
        InboundReplyService service = new InboundReplyService(deliveryService,
                List.of(providerThrowing(ChannelType.QQ, tooEarly)));

        Throwable thrown = catchThrowable(
                () -> service.reply(messageId, new CreateInboundReplyRequest("reply-4", "done")));

        assertThat(thrown).isInstanceOf(InboundReplyDeferredException.class);
        assertThat(((InboundReplyDeferredException) thrown).retryAfterSeconds()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 422})
    void shouldPreserveProviderPermanentStatus(int statusCode) {
        DeliveryService deliveryService = mock(DeliveryService.class);
        UUID messageId = UUID.randomUUID();
        when(deliveryService.inbound(messageId)).thenReturn(new InboundMessageView(messageId, 1L, ChannelType.QQ,
                "qq:private:1", "qq:private:2", "2", null, "message", Instant.now(), Instant.now(), List.of(),
                false));
        HttpClientErrorException providerFailure = HttpClientErrorException.create(
                HttpStatusCode.valueOf(statusCode), "Provider failure", new HttpHeaders(), new byte[0],
                StandardCharsets.UTF_8);
        InboundReplyService service = new InboundReplyService(deliveryService,
                List.of(providerThrowing(ChannelType.QQ, providerFailure)));

        Throwable thrown = catchThrowable(
                () -> service.reply(messageId, new CreateInboundReplyRequest("reply-5", "done")));

        assertThat(thrown).isInstanceOf(InboundReplyProviderFailureException.class).hasCause(providerFailure);
        assertThat(((InboundReplyProviderFailureException) thrown).statusCode()).isEqualTo(statusCode);
    }

    private InboundReplyProvider providerThrowing(ChannelType channelType, RuntimeException exception) {
        return new InboundReplyProvider() {
            @Override
            public ChannelType channelType() {
                return channelType;
            }

            @Override
            public void reply(InboundMessageView message, String idempotencyKey, String body) {
                // 将预设的 Connector HTTP 结果注入回复链路。
                throw exception;
            }
        };
    }
}
