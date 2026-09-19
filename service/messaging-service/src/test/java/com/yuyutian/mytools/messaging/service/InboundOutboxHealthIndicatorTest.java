package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入站消息 Outbox 健康状态测试。
 */
class InboundOutboxHealthIndicatorTest {

    @Test
    void shouldReportDownWithSeparateDeadLetterCounts() {
        MessagingRepository repository = mock(MessagingRepository.class);
        when(repository.countDeadMessageReceivedEvents()).thenReturn(2);
        when(repository.countDeadOneBotAcceptanceEvents()).thenReturn(1);
        InboundOutboxHealthIndicator indicator = new InboundOutboxHealthIndicator(repository);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("deadMessageReceivedEvents", 2)
                .containsEntry("deadOneBotForwardAcceptedEvents", 1);
    }

    @Test
    void shouldReportUpWithoutDeadLetters() {
        MessagingRepository repository = mock(MessagingRepository.class);
        InboundOutboxHealthIndicator indicator = new InboundOutboxHealthIndicator(repository);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
