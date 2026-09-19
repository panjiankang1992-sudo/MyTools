package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.service.InboundReplyDeferredException;
import com.yuyutian.mytools.messaging.service.InboundReplyProviderFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息服务异常 HTTP 契约测试。
 */
class MessagingExceptionHandlerTest {

    private final MessagingExceptionHandler handler = new MessagingExceptionHandler();

    @Test
    void shouldReturnDeferredStatusAndRetryAfter() {
        ResponseEntity<Map<String, String>> response = handler.handleInboundReplyDeferred(
                new InboundReplyDeferredException(17, new IllegalStateException("deferred")));

        assertThat(response.getStatusCode().value()).isEqualTo(425);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("17");
        assertThat(response.getBody()).containsEntry("code", ErrorCode.INBOUND_REPLY_DEFERRED.code())
                .containsEntry("status", "DEFERRED");
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 422})
    void shouldPreservePermanentProviderStatus(int statusCode) {
        ResponseEntity<Map<String, String>> response = handler.handleInboundReplyProviderFailure(
                new InboundReplyProviderFailureException(statusCode, new IllegalStateException("rejected")));

        assertThat(response.getStatusCode().value()).isEqualTo(statusCode);
        assertThat(response.getHeaders()).doesNotContainKey(HttpHeaders.RETRY_AFTER);
        assertThat(response.getBody()).containsEntry("code", ErrorCode.INBOUND_REPLY_REJECTED.code())
                .containsEntry("status", "REJECTED");
    }
}
