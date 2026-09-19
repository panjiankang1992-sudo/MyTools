package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 自动化完成通知健康检查测试。
 */
class CompletionOutboxHealthIndicatorTest {

    @Test
    void shouldReportDownWhenDeadCompletionExists() {
        AutomationRepository repository = mock(AutomationRepository.class);
        when(repository.countDeadCompletions()).thenReturn(2);

        var health = new CompletionOutboxHealthIndicator(repository).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("deadCount", 2);
    }
}
