package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(QueueDispatchTestSchema.class)
class GpuDispatchAdmissionTest {
    @Autowired private TaskInstanceService tasks;
    @Autowired private TaskDefinitionService definitions;
    @Autowired private TaskStepService steps;
    @Autowired private ExecutionTopologyService topology;
    @Autowired private TaskDispatchService dispatch;
    @Autowired private TaskDeadlineService deadlines;
    @Autowired private JdbcTemplate jdbc;

    private record Fixture(UUID cluster, UUID node, UUID instance) { }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topology.createCluster(new CreateExecutionClusterRequest(
                "gpu_admission_" + suffix, "GPU admission test", "LEAST_RUNNING", 8, Map.of(), true));
        UUID instance = UUID.randomUUID();
        var node = topology.registerNode(new RegisterExecutorNodeRequest(
                "gpu_node_" + suffix, instance.toString(), Map.of(), Map.of(), 8, Set.of(cluster.name())));
        return new Fixture(cluster.id(), node.id(), instance);
    }

    private TaskInstanceView task(Fixture fixture, String script, int priority) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var definition = definitions.create(new CreateTaskDefinitionRequest(
                "gpu_test_" + suffix, "GPU admission fixture", TaskType.IMMEDIATE, 3600,
                fixture.cluster(), null, null, ExecutionMode.SINGLE_NODE, true, 4,
                "QUEUE", "IGNORE", Map.of(), Map.of()));
        steps.create(definition.id(), new CreateTaskStepRequest("run", "GPU step", StepKind.NORMAL,
                script, "1.0.0", "scripts/main.py", List.of(), true, 180, FailurePolicy.FAIL_TASK, 10, 1));
        return tasks.create(new CreateTaskRequest(definition.name(), suffix, "TEST", suffix, null, priority, Map.of()));
    }

    private ClaimTaskRequest request(Fixture fixture) {
        return new ClaimTaskRequest(fixture.node(), fixture.instance(), UUID.randomUUID(), 60);
    }

    private void complete(ClaimedTaskView claimed) {
        dispatch.complete(claimed.executionId(), new CompleteExecutionRequest(claimed.leaseToken(), TaskStatus.SUCCEEDED));
    }

    @Test
    void videoKeepsImageAndInlineTagQueuedBeyondOldTimeoutWhileCpuCanRun() {
        var fixture = fixture();
        var video = task(fixture, "video_generate", 100);
        var running = dispatch.claim(request(fixture)).orElseThrow();
        assertEquals(video.id(), running.taskInstanceId());
        var image = task(fixture, "image_generate", 90);
        var tag = task(fixture, "media_generate_tags", 80);
        var cpu = task(fixture, "system_executor_acceptance", 50);
        var cpuRunning = dispatch.claim(request(fixture)).orElseThrow();
        assertEquals(cpu.id(), cpuRunning.taskInstanceId());
        assertTrue(dispatch.claim(request(fixture)).isEmpty());
        deadlines.expireDeadlines(Instant.now().plusSeconds(9 * 60));
        assertEquals(TaskStatus.QUEUED, tasks.get(image.id()).status());
        assertEquals(TaskStatus.QUEUED, tasks.get(tag.id()).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_execution WHERE task_instance_id IN (?, ?)",
                Integer.class, image.id().toString(), tag.id().toString()));
        complete(running);
        var next = dispatch.claim(request(fixture)).orElseThrow();
        assertEquals(image.id(), next.taskInstanceId());
        complete(next);
        next = dispatch.claim(request(fixture)).orElseThrow();
        assertEquals(tag.id(), next.taskInstanceId());
        complete(next);
        complete(cpuRunning);
    }

    @Test
    void videoWaitsForExistingTagWithoutQueueExpiry() {
        var fixture = fixture();
        task(fixture, "media_generate_tags", 50);
        var running = dispatch.claim(request(fixture)).orElseThrow();
        var video = task(fixture, "video_generate", 100);
        assertTrue(dispatch.claim(request(fixture)).isEmpty());
        Timestamp deadline = jdbc.queryForObject("SELECT dispatch_deadline_at FROM task_instance WHERE id = ?",
                Timestamp.class, video.id().toString());
        assertNull(deadline);
        complete(running);
        var next = dispatch.claim(request(fixture)).orElseThrow();
        assertEquals(video.id(), next.taskInstanceId());
        complete(next);
    }

    @Test
    void simultaneousClaimsDoNotStartVideoAndImageTogether() throws Exception {
        var fixture = fixture();
        task(fixture, "video_generate", 100);
        task(fixture, "image_generate", 90);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return dispatch.claim(request(fixture)); });
            var second = executor.submit(() -> { start.await(); return dispatch.claim(request(fixture)); });
            start.countDown();
            var a = first.get();
            var b = second.get();
            assertEquals(1, (a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0));
            complete(a.orElseGet(b::orElseThrow));
        }
    }

    @Test
    void nonGpuQueueWaitsWithoutDeadline() {
        var fixture = fixture();
        var cpu = task(fixture, "system_executor_acceptance", 50);
        deadlines.expireDeadlines(Instant.now().plusSeconds(301));
        assertEquals(TaskStatus.QUEUED, tasks.get(cpu.id()).status());
    }
}
