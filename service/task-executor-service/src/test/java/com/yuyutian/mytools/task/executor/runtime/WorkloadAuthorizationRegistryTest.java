package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionLease;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadAuthorizationRegistryTest {
    private static final String THUMBPRINT = "a".repeat(43);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private final WorkloadAuthorizationRegistry registry = new WorkloadAuthorizationRegistry(mapper, clock, THUMBPRINT);
    @TempDir Path directory;

    @Test
    void shouldDeserializeWithoutSerializingAnyAuthorization() throws Exception {
        var task = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        task.verifyDefinitionDigest();
        var received = mapper.readValue(WorkloadFixtures.transport(task), ClaimedTask.class);
        assertEquals(task.workloadAssertion(), received.workloadAssertion());
        assertEquals(task.workloadAssertionExpiresAt(), received.workloadAssertionExpiresAt());
        String persisted = mapper.writeValueAsString(received);
        assertFalse(persisted.contains("workloadAssertion"));
        assertFalse(persisted.contains(task.workloadAssertion()));
        assertNull(mapper.readValue(persisted, ClaimedTask.class).workloadAssertion());
        assertFalse(received.toString().contains(task.leaseToken().toString()));
        assertFalse(received.toString().contains(task.workloadAssertion()));
        var lease = mapper.readValue("{\"leaseUntil\":\"" + task.leaseUntil()
                + "\",\"cancelRequested\":false,\"leaseState\":\"ACTIVE\",\"workloadAssertion\":\""
                + task.workloadAssertion() + "\",\"workloadAssertionExpiresAt\":\"" + task.workloadAssertionExpiresAt()
                + "\"}", ExecutionLease.class);
        assertEquals(task.workloadAssertion(), lease.workloadAssertion());
        assertFalse(mapper.writeValueAsString(lease).contains("workloadAssertion"));
        assertFalse(lease.toString().contains(task.workloadAssertion()));
        registry.acceptClaim(received);
        assertFalse(mapper.writeValueAsString(registry.current(task.executionId())).contains(task.workloadAssertion()));
    }

    @Test
    void shouldNeverPutAssertionsInClaimOrReportSqliteWal() throws Exception {
        var task = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        var properties = new ExecutorProperties("fixture", "http://127.0.0.1:1", directory.resolve("tasks"),
                directory.resolve("scripts"), directory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 4, Map.of(), Map.of(), Set.of(), false, Map.of());
        try (var journal = new ExecutionReportJournal(properties, mapper)) {
            journal.recordClaim(task);
            journal.persistStep(task, task.steps().getFirst(), 1, "SUCCEEDED", 0, Map.of("status", "done"), null, null);
            journal.persistCompletion(task, "SUCCEEDED");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + journal.databasePath());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT payload_json FROM journal_execution UNION ALL SELECT payload_json FROM journal_report")) {
                int count = 0;
                while (rows.next()) {
                    String json = rows.getString(1);
                    assertFalse(json.contains("workloadAssertion"));
                    assertFalse(json.contains(task.workloadAssertion()));
                    count++;
                }
                assertEquals(3, count);
            }
        }
    }

    @Test
    void shouldRotateInMemoryAndIgnoreOlderHeartbeat() throws Exception {
        var task = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        registry.acceptClaim(task);
        var second = heartbeat(task, 2);
        registry.acceptHeartbeat(task, second);
        assertEquals(second.workloadAssertion(), registry.current(task.executionId()).token());
        registry.acceptHeartbeat(task, new ExecutionLease(task.leaseUntil(), false, "ACTIVE",
                task.workloadAssertion(), task.workloadAssertionExpiresAt()));
        assertEquals(2, registry.current(task.executionId()).generation());
    }

    @Test
    void shouldNotResurrectAfterCancellationCompletionOrMissingClaim() throws Exception {
        var task = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        assertThrows(IOException.class, () -> registry.acceptHeartbeat(task, heartbeat(task, 2)));
        registry.acceptClaim(task);
        registry.acceptHeartbeat(task, new ExecutionLease(task.leaseUntil(), true, "REVOKED"));
        assertThrows(IOException.class, () -> registry.current(task.executionId()));
        assertThrows(IOException.class, () -> registry.acceptHeartbeat(task, heartbeat(task, 3)));
        var second = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        registry.acceptClaim(second);
        registry.close(second.executionId());
        assertThrows(IOException.class, () -> registry.acceptHeartbeat(second, heartbeat(second, 2)));
    }

    @Test
    void shouldStopServingAnExpiredTokenWithoutWaitingForScheduler() throws Exception {
        var task = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        registry.acceptClaim(task);
        clock.now = clock.now.plusSeconds(60);
        assertThrows(IOException.class, () -> registry.current(task.executionId()));
        // 新任务领取不能把尚在有效租约内、等待续期的旧任务误清理。
        registry.acceptClaim(WorkloadFixtures.task(clock.instant(), THUMBPRINT));
        // 只要权威租约仍有效，Scheduler 续期可替换过期的短 token；Reader 仍逐请求查撤销。
        registry.acceptHeartbeat(task, heartbeat(task, 2));
        assertEquals(2, registry.current(task.executionId()).generation());
    }

    @Test
    void shouldKeepOneExecutionAcrossSimulatedNineHundredSeconds() throws Exception {
        var task = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        registry.acceptClaim(task);
        for (int index = 1; index <= 44; index++) {
            clock.now = clock.now.plusSeconds(20);
            Instant lease = clock.now.plusSeconds(90).isAfter(task.deadlineAt()) ? task.deadlineAt() : clock.now.plusSeconds(90);
            Instant expiry = clock.now.plusSeconds(60).isAfter(lease) ? lease : clock.now.plusSeconds(60);
            String token = WorkloadFixtures.token(task, clock.now, lease, THUMBPRINT, index + 1, Map.of());
            registry.acceptHeartbeat(task, new ExecutionLease(lease, false, "ACTIVE", token, expiry));
            assertEquals(index + 1, registry.current(task.executionId()).generation());
        }
        clock.now = clock.now.plusSeconds(20);
        assertThrows(IOException.class, () -> registry.current(task.executionId()));
    }

    @Test
    void shouldRejectWrongScopeCertificateAndIncompleteAuthorization() throws Exception {
        var task = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        List<Map<String, Object>> invalid = List.of(Map.of("aud", "reader-provider-probe-internal"),
                Map.of("cnf", Map.of("x5t#S256", "b".repeat(43))), Map.of("fencingToken", 2),
                Map.of("executionId", UUID.randomUUID().toString()), Map.of("taskParametersSha256", "b".repeat(64)),
                Map.of("ownerId", "forbidden"), Map.of("assertionGeneration", 0), Map.of("exp", clock.now.plusSeconds(121).getEpochSecond()));
        for (var change : invalid) {
            var altered = WorkloadFixtures.withToken(task, WorkloadFixtures.token(task, clock.now, task.leaseUntil(),
                    THUMBPRINT, 1, change), task.workloadAssertionExpiresAt());
            IOException exception = assertThrows(IOException.class, () -> registry.acceptClaim(altered));
            assertNull(exception.getCause());
            assertFalse(exception.getMessage().contains(altered.workloadAssertion()));
        }
        assertThrows(IOException.class, () -> registry.acceptClaim(WorkloadFixtures.withToken(task, null, null)));
        assertThrows(IOException.class, () -> new WorkloadAuthorizationRegistry(mapper, clock, null).acceptClaim(task));
    }

    @Test
    void shouldCloseOnMalformedRenewalAndRejectUnrelatedTaskCredentials() throws Exception {
        var task = WorkloadFixtures.task(clock.instant(), THUMBPRINT);
        registry.acceptClaim(task);
        assertThrows(IOException.class, () -> registry.acceptHeartbeat(task, new ExecutionLease(task.leaseUntil(), false, "ACTIVE")));
        assertThrows(IOException.class, () -> registry.current(task.executionId()));
        var ordinary = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "ordinary", UUID.randomUUID(),
                task.leaseUntil(), task.deadlineAt(), Map.of(), List.of());
        registry.acceptClaim(ordinary);
        assertThrows(IOException.class, () -> registry.acceptClaim(WorkloadFixtures.withToken(ordinary, task.workloadAssertion(), task.workloadAssertionExpiresAt())));
    }

    private ExecutionLease heartbeat(ClaimedTask task, long generation) throws Exception {
        Instant lease = clock.now.plusSeconds(90);
        return new ExecutionLease(lease, false, "ACTIVE",
                WorkloadFixtures.token(task, clock.now, lease, THUMBPRINT, generation, Map.of()), clock.now.plusSeconds(60));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T00:00:00Z");
        /** 返回测试时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 返回固定时区测试时钟。 */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 返回可推进的测试时间。 */
        @Override public Instant instant() { return now; }
    }
}
