package com.yuyutian.mytools.messaging.provider;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * QQ 原会话回复适配器契约测试。
 */
class QqInboundReplyProviderTest {

    @Test
    void shouldKeepTooEarlyStatusAndRetryAfter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://qq.test/internal/v1/messages/text"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer qq-token"))
                .andRespond(withStatus(HttpStatusCode.valueOf(425))
                        .header(HttpHeaders.RETRY_AFTER, "17"));
        QqInboundReplyProvider provider = new QqInboundReplyProvider(builder, properties());
        UUID messageId = UUID.randomUUID();
        InboundMessageView message = new InboundMessageView(messageId, 1L, ChannelType.QQ,
                "qq:private:source-message", "qq:private:target", "target", null, "message",
                Instant.now(), Instant.now(), List.of(), false);

        Throwable thrown = catchThrowable(() -> provider.reply(message, "reply-key", "done"));

        assertThat(thrown).isInstanceOf(HttpClientErrorException.class);
        HttpClientErrorException response = (HttpClientErrorException) thrown;
        assertThat(response.getStatusCode().value()).isEqualTo(425);
        assertThat(response.getResponseHeaders()).isNotNull();
        assertThat(response.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("17");
        server.verify();
    }

    private MessagingProperties properties() {
        return new MessagingProperties(null, null, null, null, null, false, 0, 0,
                false, null, null, null, null, "http://qq.test", "qq-token", null, null);
    }
}
