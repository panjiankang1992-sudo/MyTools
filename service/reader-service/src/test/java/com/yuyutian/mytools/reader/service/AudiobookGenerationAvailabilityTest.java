package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.config.ReaderProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudiobookGenerationAvailabilityTest {

    @Test
    void shouldAllowAllOwnersWhenFeatureIsEnabledWithoutAllowlist() {
        var availability = availability(true, Set.of());

        assertThatCode(() -> availability.requireNewWorkAllowed(901L)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNewWorkWhenFeatureIsDisabledOrOwnerIsNotAllowlisted() {
        assertThatThrownBy(() -> availability(false, Set.of()).requireNewWorkAllowed(902L))
                .isInstanceOf(AudiobookGenerationUnavailableException.class);
        assertThatThrownBy(() -> availability(true, Set.of(903L)).requireNewWorkAllowed(902L))
                .isInstanceOf(AudiobookGenerationUnavailableException.class);
        assertThatCode(() -> availability(true, Set.of(903L)).requireNewWorkAllowed(903L))
                .doesNotThrowAnyException();
    }

    private AudiobookGenerationAvailability availability(boolean enabled, Set<Long> allowedOwnerIds) {
        return new AudiobookGenerationAvailability(new ReaderProperties("scheduler", "token", "managed", "runtime",
                "runtime-key", "storage", "storage-token", 1_500_000L, 3_000_000L, enabled, allowedOwnerIds));
    }
}
