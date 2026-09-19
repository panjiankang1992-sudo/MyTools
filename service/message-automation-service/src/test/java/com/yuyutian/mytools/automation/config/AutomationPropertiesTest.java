package com.yuyutian.mytools.automation.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动化配置边界测试。
 */
class AutomationPropertiesTest {

    @Test
    void shouldUseTenBoundedCompletionDeliveryAttemptsByDefault() throws IOException {
        var sources = new YamlPropertySourceLoader().load("application",
                new FileSystemResource("src/main/resources/application.yml"));

        assertThat(sources).isNotEmpty();
        assertThat(sources.getFirst().getProperty("automation.completion-relay-max-attempts"))
                .isEqualTo("${MESSAGE_AUTOMATION_COMPLETION_RELAY_MAX_ATTEMPTS:10}");
        assertThat(sources.getFirst().getProperty("automation.action-status-poll-delay-ms"))
                .isEqualTo("${MESSAGE_AUTOMATION_ACTION_STATUS_POLL_DELAY_MS:250}");
    }

    @Test
    void shouldRejectCompletionDeliveryAttemptsAboveBound() {
        AutomationProperties properties = new AutomationProperties(
                "internal", "http://messaging.test", "messaging-token",
                "http://download.test", "download-token", true, 50, 100, 11, 500);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .anySatisfy(violation -> {
                        assertThat(violation.getPropertyPath().toString())
                                .isEqualTo("completionRelayMaxAttempts");
                        assertThat(violation.getInvalidValue()).isEqualTo(11);
                    });
        }
    }

    @Test
    void shouldRejectActionStatusPollDelayBelowBound() {
        AutomationProperties properties = new AutomationProperties(
                "internal", "http://messaging.test", "messaging-token",
                "http://download.test", "download-token", true, 50, 100,
                10, 500, 4, 180, 8, 49);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .anySatisfy(violation -> {
                        assertThat(violation.getPropertyPath().toString())
                                .isEqualTo("actionStatusPollDelayMs");
                        assertThat(violation.getInvalidValue()).isEqualTo(49);
                    });
        }
    }
}
