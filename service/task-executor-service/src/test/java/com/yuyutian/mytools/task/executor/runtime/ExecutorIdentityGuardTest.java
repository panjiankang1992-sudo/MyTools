package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutorIdentityGuardTest {

    @Test
    void shouldRejectRootWhenEnforcementIsEnabled() {
        ExecutorIdentityGuard guard = new ExecutorIdentityGuard(properties(true), () -> 0, () -> "root");

        assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertEquals("DOWN", guard.health().getStatus().getCode());
    }

    @Test
    void shouldAllowNonRootWhenEnforcementIsEnabled() {
        ExecutorIdentityGuard guard = new ExecutorIdentityGuard(properties(true), () -> 1000, () -> "mytools");

        assertDoesNotThrow(guard::afterPropertiesSet);
        assertEquals("UP", guard.health().getStatus().getCode());
    }

    @Test
    void shouldAllowExplicitDevelopmentOverrideButExposeUnhealthyIdentity() {
        ExecutorIdentityGuard guard = new ExecutorIdentityGuard(properties(false), () -> 0, () -> "root");

        assertDoesNotThrow(guard::afterPropertiesSet);
        assertEquals("DOWN", guard.health().getStatus().getCode());
    }

    private ExecutorProperties properties(boolean requireNonRoot) {
        return new ExecutorProperties("executor-test", "http://127.0.0.1:23410", "", Path.of("work"),
                Path.of("scripts"), Path.of("sdk"), Path.of("python3"), 10, 1, 60, 30, 1, 0,
                Map.of(), Map.of(), Set.of(), false, requireNonRoot, Map.of());
    }
}
