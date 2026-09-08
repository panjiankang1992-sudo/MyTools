package com.yuyutian.mytools.task.scheduler.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskOutboxHealthIndicatorTest {

    @Test
    void shouldRemainHealthyWhenWebhookDeliveryIsDisabled() {
        TaskOutboxProperties properties = new TaskOutboxProperties("", "", 50, 10, 300);

        var health = new TaskOutboxHealthIndicator(null, properties).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("enabled", false);
    }
}
