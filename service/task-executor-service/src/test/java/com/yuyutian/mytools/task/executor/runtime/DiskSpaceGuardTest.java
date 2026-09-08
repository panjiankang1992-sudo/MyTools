package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorDiskProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskSpaceGuardTest {

    @Test
    void shouldAcceptCapacityAboveBothThresholds() {
        DiskSpaceGuard guard = guard(200, 1_000, 100, 10);

        assertTrue(guard.hasCapacity());
        assertEquals("UP", guard.health().getStatus().getCode());
    }

    @Test
    void shouldRejectCapacityBelowAbsoluteThreshold() {
        DiskSpaceGuard guard = guard(99, 1_000, 100, 5);

        assertFalse(guard.hasCapacity());
        assertEquals("DOWN", guard.health().getStatus().getCode());
    }

    @Test
    void shouldRejectCapacityBelowPercentageThreshold() {
        DiskSpaceGuard guard = guard(149, 1_000, 100, 15);

        assertFalse(guard.hasCapacity());
        assertEquals(150L, guard.health().getDetails().get("requiredUsableBytes"));
    }

    @Test
    void shouldFailClosedWhenDiskUsageCannotBeRead() {
        DiskSpaceGuard guard = new DiskSpaceGuard(new ExecutorDiskProperties(100, 5), () -> {
            throw new IOException("unavailable");
        });

        assertFalse(guard.hasCapacity());
        assertEquals("DOWN", guard.health().getStatus().getCode());
    }

    private DiskSpaceGuard guard(long usable, long total, long minimumBytes, int minimumPercent) {
        return new DiskSpaceGuard(new ExecutorDiskProperties(minimumBytes, minimumPercent),
                () -> new DiskSpaceGuard.DiskUsage(usable, total));
    }
}
