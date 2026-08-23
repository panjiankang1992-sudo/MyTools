package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CronTriggerPlannerTest {

    @Test
    void shouldRunOneMisfiredOccurrenceAndAdvanceAfterNow() {
        var plan = CronTriggerPlanner.plan(definition("RUN_ONCE"),
                Instant.parse("2026-01-01T00:00:01Z"), Instant.parse("2026-01-01T00:00:10Z"), 100);

        assertEquals(java.util.List.of(Instant.parse("2026-01-01T00:00:01Z")), plan.fireTimes());
        assertEquals(Instant.parse("2026-01-01T00:00:11Z"), plan.nextFireAt());
    }

    @Test
    void shouldIgnoreMissedOccurrences() {
        var plan = CronTriggerPlanner.plan(definition("IGNORE"),
                Instant.parse("2026-01-01T00:00:01Z"), Instant.parse("2026-01-01T00:00:10Z"), 100);

        assertEquals(java.util.List.of(), plan.fireTimes());
        assertEquals(Instant.parse("2026-01-01T00:00:11Z"), plan.nextFireAt());
    }

    @Test
    void shouldBoundCatchUpPerScan() {
        var plan = CronTriggerPlanner.plan(definition("CATCH_UP"),
                Instant.parse("2026-01-01T00:00:01Z"), Instant.parse("2026-01-01T00:00:05Z"), 3);

        assertEquals(3, plan.fireTimes().size());
        assertEquals(Instant.parse("2026-01-01T00:00:03Z"), plan.lastProcessedAt());
        assertEquals(Instant.parse("2026-01-01T00:00:04Z"), plan.nextFireAt());
    }

    private TaskDefinitionView definition(String misfirePolicy) {
        return new TaskDefinitionView(
                UUID.randomUUID(), "scheduled_test", "Scheduled test", TaskType.SCHEDULED, 60,
                UUID.randomUUID(), "* * * * * *", "UTC", ExecutionMode.SINGLE_NODE, true, 1,
                "ALLOW", misfirePolicy, Map.of(), Map.of(), 1,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    }
}
