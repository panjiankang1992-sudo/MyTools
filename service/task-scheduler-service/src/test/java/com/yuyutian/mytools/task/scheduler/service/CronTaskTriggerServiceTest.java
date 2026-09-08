package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import com.yuyutian.mytools.task.scheduler.repository.TaskDefinitionRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskScheduleCursorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CronTaskTriggerServiceTest {

    @Autowired
    private TaskDefinitionService definitionService;

    @Autowired
    private TaskDefinitionRepository definitionRepository;

    @Autowired
    private TaskScheduleCursorRepository cursorRepository;

    @Autowired
    private TaskInstanceRepository instanceRepository;

    @Autowired
    private TaskInstanceService instanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldProcessDueDefinitionsInBoundedBatches() {
        Instant now = Instant.now();
        var first = createScheduledDefinition("cron_batch_first_");
        var second = createScheduledDefinition("cron_batch_second_");
        var third = createScheduledDefinition("cron_batch_third_");
        deferAllCursors(now);
        makeDue(first.id(), now);
        makeDue(second.id(), now);
        makeDue(third.id(), now);
        CronTaskTriggerService service = triggerService(2);

        service.triggerDueTasks(now);

        assertEquals(2, scheduledInstanceCount(first.id(), second.id(), third.id()));
        service.triggerDueTasks(now);
        assertEquals(3, scheduledInstanceCount(first.id(), second.id(), third.id()));
    }

    @Test
    void shouldCreateOneInstanceWhenSchedulersClaimConcurrently() throws Exception {
        Instant now = Instant.now();
        var definition = createScheduledDefinition("cron_concurrent_");
        deferAllCursors(now);
        makeDue(definition.id(), now);
        CronTaskTriggerService first = triggerService(64);
        CronTaskTriggerService second = triggerService(64);
        CountDownLatch startGate = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstRun = executor.submit(() -> {
                startGate.await();
                first.triggerDueTasks(now);
                return null;
            });
            var secondRun = executor.submit(() -> {
                startGate.await();
                second.triggerDueTasks(now);
                return null;
            });
            startGate.countDown();
            firstRun.get();
            secondRun.get();
        }

        assertEquals(1, scheduledInstanceCount(definition.id()));
    }

    private com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView createScheduledDefinition(String prefix) {
        return definitionService.create(new CreateTaskDefinitionRequest(
                prefix + UUID.randomUUID().toString().replace("-", ""), "Cron test", TaskType.SCHEDULED,
                60, null, "* * * * * *", "UTC", ExecutionMode.SINGLE_NODE, true, 1,
                "SKIP", "RUN_ONCE", Map.of(), Map.of()));
    }

    private CronTaskTriggerService triggerService(int batchSize) {
        return new CronTaskTriggerService(definitionRepository, cursorRepository, instanceRepository,
                instanceService, 10, 30, batchSize);
    }

    private void deferAllCursors(Instant now) {
        jdbcTemplate.update("UPDATE task_schedule_cursor SET next_fire_at = ?, lease_owner = NULL, lease_until = NULL",
                Timestamp.from(now.plusSeconds(3600)));
    }

    private void makeDue(UUID definitionId, Instant now) {
        jdbcTemplate.update("UPDATE task_schedule_cursor SET next_fire_at = ? WHERE task_definition_id = ?",
                Timestamp.from(now.minusSeconds(2)), definitionId.toString());
    }

    private int scheduledInstanceCount(UUID... definitionIds) {
        int count = 0;
        for (UUID definitionId : definitionIds) {
            Integer value = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_instance WHERE task_definition_id = ?",
                    Integer.class, definitionId.toString());
            count += value == null ? 0 : value;
        }
        return count;
    }
}
