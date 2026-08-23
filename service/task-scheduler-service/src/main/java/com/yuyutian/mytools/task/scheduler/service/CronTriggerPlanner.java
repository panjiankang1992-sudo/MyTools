package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时触发时间与误触发策略的纯计算器。
 */
final class CronTriggerPlanner {

    private static final Duration MISFIRE_TOLERANCE = Duration.ofSeconds(1);

    private CronTriggerPlanner() {
    }

    static TriggerPlan plan(TaskDefinitionView definition, Instant firstDue, Instant now, int maxCatchUp) {
        if (maxCatchUp < 1) {
            throw new IllegalArgumentException("Cron max catch-up must be positive");
        }
        boolean misfired = firstDue.isBefore(now.minus(MISFIRE_TOLERANCE));
        if (misfired && "IGNORE".equals(definition.misfirePolicy())) {
            return new TriggerPlan(List.of(), now, nextFire(definition, now));
        }
        if (misfired && "RUN_ONCE".equals(definition.misfirePolicy())) {
            return new TriggerPlan(List.of(firstDue), firstDue, nextFire(definition, now));
        }
        ArrayList<Instant> fireTimes = new ArrayList<>();
        Instant candidate = firstDue;
        while (!candidate.isAfter(now) && fireTimes.size() < maxCatchUp) {
            fireTimes.add(candidate);
            candidate = nextFire(definition, candidate);
        }
        return new TriggerPlan(fireTimes, fireTimes.isEmpty() ? now : fireTimes.getLast(), candidate);
    }

    static Instant nextFire(TaskDefinitionView definition, Instant after) {
        ZoneId zone = ZoneId.of(definition.cronTimezone() == null || definition.cronTimezone().isBlank()
                ? "UTC" : definition.cronTimezone());
        ZonedDateTime next = CronExpression.parse(definition.cronExpression())
                .next(ZonedDateTime.ofInstant(after, zone));
        if (next == null) {
            throw new IllegalArgumentException("Cron expression has no next fire time");
        }
        return next.toInstant();
    }

    record TriggerPlan(List<Instant> fireTimes, Instant lastProcessedAt, Instant nextFireAt) {
    }
}
