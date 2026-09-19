package com.yuyutian.mytools.auth.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationMailRoutingServiceTest {

    @Test
    void shouldRouteNormalizedEmailToSameBucket() {
        RegistrationMailSidecarProperties properties = properties("CANARY", 50);
        RegistrationMailRoutingService router = new RegistrationMailRoutingService(properties);

        assertThat(router.route(" User@Example.com ")).isEqualTo(router.route("user@example.com"));
    }

    @Test
    void shouldRespectBoundaryModesAndPercentages() {
        assertThat(new RegistrationMailRoutingService(properties("LEGACY", 100)).route("user@example.com"))
                .isEqualTo(RegistrationMailRoute.LEGACY);
        assertThat(new RegistrationMailRoutingService(properties("SHADOW", 0)).route("user@example.com"))
                .isEqualTo(RegistrationMailRoute.LEGACY);
        assertThat(new RegistrationMailRoutingService(properties("PRIMARY", 0)).route("user@example.com"))
                .isEqualTo(RegistrationMailRoute.MESSAGING);
        assertThat(new RegistrationMailRoutingService(properties("CANARY", 0)).route("user@example.com"))
                .isEqualTo(RegistrationMailRoute.LEGACY);
        assertThat(new RegistrationMailRoutingService(properties("CANARY", 100)).route("user@example.com"))
                .isEqualTo(RegistrationMailRoute.MESSAGING);
    }

    @Test
    void shouldRejectUnsafeCanaryConfiguration() {
        RegistrationMailSidecarProperties properties = properties("CANARY", 101);

        assertThatThrownBy(() -> new RegistrationMailRoutingService(properties))
                .isInstanceOf(IllegalStateException.class);
        properties.setCanaryPercent(10);
        properties.setRoutingKey("short");
        assertThatThrownBy(() -> new RegistrationMailRoutingService(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    private RegistrationMailSidecarProperties properties(String mode, int percent) {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setMode(mode);
        properties.setCanaryPercent(percent);
        properties.setRoutingKey("stable-routing-key-that-is-at-least-32-bytes");
        return properties;
    }
}
