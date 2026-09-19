package com.yuyutian.mytools.task.executor.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutorPollIntervalTest {

    @Test
    void shouldDefaultToTwoHundredFiftyMilliseconds() {
        ExecutorProperties properties = properties(0, 0);

        assertEquals(250L, new ExecutorPollInterval(properties).milliseconds());
    }

    @Test
    void shouldKeepLegacySecondsConfigurationCompatible() {
        ExecutorProperties properties = properties(2, 0);

        assertEquals(2_000L, new ExecutorPollInterval(properties).milliseconds());
    }

    @Test
    void shouldPreferMillisecondsConfiguration() {
        ExecutorProperties properties = properties(2, 125);

        assertEquals(125L, new ExecutorPollInterval(properties).milliseconds());
    }

    @Test
    void shouldReservePublishedChildChainDepthAndScaleWithConcurrency() {
        assertEquals(3, properties(0, 0).effectiveReservedChildTaskSlots());
        assertEquals(4, properties(0, 0, 8, 0).effectiveReservedChildTaskSlots());
        assertEquals(0, properties(0, 0, 1, 0).effectiveReservedChildTaskSlots());
    }

    @Test
    void shouldRejectReservationThatCannotFitPublishedChildChain() {
        assertThrows(IllegalArgumentException.class, () -> properties(0, 0, 8, 2));
    }

    private ExecutorProperties properties(long pollSeconds, long pollMilliseconds) {
        return properties(pollSeconds, pollMilliseconds, 4, 0);
    }

    private ExecutorProperties properties(long pollSeconds, long pollMilliseconds, int maximumTasks,
                                          int reservedChildSlots) {
        return new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23410", "", Path.of("work"), Path.of("scripts"),
                Path.of("sdk"), Path.of("python3"), 10, pollSeconds, pollMilliseconds, 60, 30, maximumTasks,
                reservedChildSlots, 0, Map.of(), Map.of(), Set.of(), false, false, Map.of());
    }
}
