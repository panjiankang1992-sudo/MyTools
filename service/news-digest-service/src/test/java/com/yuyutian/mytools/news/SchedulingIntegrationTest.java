package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.mapper.SchedulingRepository;
import com.yuyutian.mytools.news.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:news-scheduling;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "news.internal-token=test-proxy", "news.admin-token=test-admin", "news.region.node-id=executor-primary",
        "news.scheduling.enabled=true", "news.scheduling.timer-enabled=false",
        "news.scheduling.business-token=test-scheduler-token-at-least-thirty-two-characters",
        "news.scheduling.worker-token=test-job-token-at-least-thirty-two-characters"
})
@AutoConfigureMockMvc
class SchedulingIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    @Autowired private SchedulingService service;
    @Autowired private SchedulingRepository repository;
    @Autowired private EditionService editions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private MockMvc mvc;
    @MockitoBean private NewsSchedulerClient scheduler;

    @BeforeEach
    void resetState() {
        for (String table : List.of("news_task_outbox", "news_daily_run", "news_subscription", "news_region_snapshot", "news_edition")) jdbc.update("DELETE FROM " + table);
        Fixtures.resetControl(jdbc);
        Fixtures.resetRegistry(jdbc);
        clock.now.set(NOW);
        reset(scheduler);
    }

    @Test
    void concurrentScansProduceOneRunAndOneOutboxAcrossSubscriptionEdits() throws Exception {
        var sub = service.change(55, change(0, true));
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) results.add(pool.submit(service::scan));
            for (var result : results) result.get();
        }
        assertThat(service.runs(55, 30)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_task_outbox", Integer.class)).isEqualTo(1);
        service.change(55, change(sub.revision(), true));
        assertThat(service.scan()).isZero();
        assertThat(service.runs(55, 30)).hasSize(1);
        assertThat(service.runs(55, 30).getFirst().status()).isEqualTo("CANCELLED");
    }

    @Test
    void optimisticRevisionAndOwnerIsolationAreEnforced() throws Exception {
        service.change(55, change(0, true));
        assertThatThrownBy(() -> service.change(55, change(0, false))).isInstanceOf(NewsException.class).hasMessage(ErrorCode.NEWS_014.message());
        service.scan();
        DailyRun run = service.runs(55, 30).getFirst();
        assertThat(service.runs(56, 30)).isEmpty();
        assertThatThrownBy(() -> service.run(56, run.id())).hasMessage(ErrorCode.NEWS_003.message());
        assertThatThrownBy(() -> service.cancel(56, run.id())).hasMessage(ErrorCode.NEWS_003.message());
        mvc.perform(get("/internal/v1/news/runs/" + run.id() + "?ownerId=56").header("Authorization", "Bearer test-proxy"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/internal/v1/news/subscription?ownerId=55").header("Authorization", "Bearer test-proxy")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"enabled\":true,\"zoneId\":\"Mars/Test\",\"cutoffTime\":\"08:00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void firstEnableNeverBackfillsAndRecoveryOnlyConsidersLatestDate() {
        clock.now.set(NOW.plusSeconds(1));
        var sub = service.change(55, change(0, true));
        assertThat(sub.nextDueAt()).isEqualTo(NOW.plusSeconds(86400));
        clock.now.set(NOW.plus(Duration.ofDays(5)).plusSeconds(60));
        assertThat(service.scan()).isEqualTo(1);
        DailyRun run = service.runs(55, 30).getFirst();
        assertThat(run.businessDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(run.skippedDays()).isEqualTo(4);
        assertThat(run.status()).isEqualTo("QUEUED");
        assertThat(service.scan()).isZero();
    }

    @Test
    void twelveHourBoundaryExpiresWithoutSendingToScheduler() {
        service.change(55, change(0, true));
        clock.now.set(NOW.plusSeconds(43200));
        service.scan();
        assertThat(service.runs(55, 30).getFirst().status()).isEqualTo("EXPIRED");
        assertThat(service.relay()).isZero();
        verifyNoInteractions(scheduler);
    }

    @Test
    void outboxSurvivesLostCreateResponseAndReusesOneLogicalKey() {
        DailyRun run = queued();
        UUID task = UUID.randomUUID();
        when(scheduler.create(any())).thenThrow(new NewsSchedulerClient.Failure(false)).thenReturn(new NewsSchedulerClient.TaskState(task, "QUEUED"));
        service.relay();
        assertThat(service.run(55, run.id()).errorCode()).isEqualTo("NEWS_015");
        clock.now.set(NOW.plusSeconds(5));
        service.relay();
        assertThat(service.run(55, run.id()).taskId()).isEqualTo(task);
        var argument = org.mockito.ArgumentCaptor.forClass(DailyRun.class);
        verify(scheduler, times(2)).create(argument.capture());
        assertThat(argument.getAllValues()).extracting(DailyRun::id).containsOnly(run.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_daily_run", Integer.class)).isEqualTo(1);
    }

    @Test
    void cancelledUnknownCreationIsResolvedAndThenCancelledUntilTerminal() {
        DailyRun run = queued();
        UUID task = UUID.randomUUID();
        when(scheduler.create(any())).thenThrow(new NewsSchedulerClient.Failure(false)).thenReturn(new NewsSchedulerClient.TaskState(task, "QUEUED"));
        service.relay();
        service.cancel(55, run.id());
        clock.now.set(NOW.plusSeconds(5));
        service.relay();
        when(scheduler.cancel(any())).thenReturn(new NewsSchedulerClient.TaskState(task, "CANCELLING"), new NewsSchedulerClient.TaskState(task, "CANCELLED"));
        clock.now.set(NOW.plusSeconds(6)); service.relay();
        assertThat(outbox(run.id())).isEqualTo("PENDING");
        clock.now.set(NOW.plusSeconds(7)); service.relay();
        assertThat(outbox(run.id())).isEqualTo("DONE");
        assertThat(service.run(55, run.id()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancellationBeforeAnyAttemptDoesNotCreateRemoteTask() {
        DailyRun run = queued();
        service.cancel(55, run.id());
        service.relay();
        verifyNoInteractions(scheduler);
        assertThat(outbox(run.id())).isEqualTo("DONE");
    }

    @Test
    void leaseTakeoverRejectsOldRelayButPreservesNewClaim() {
        DailyRun run = queued();
        var first = repository.claim(NOW).orElseThrow();
        assertThat(repository.claim(NOW)).isEmpty();
        var second = repository.claim(NOW.plusSeconds(121)).orElseThrow();
        assertThatThrownBy(() -> repository.reconcile(first, UUID.randomUUID(), "QUEUED", null, false, false, NOW, NOW.plusSeconds(121)))
                .isInstanceOf(NewsException.class).hasMessage(ErrorCode.NEWS_016.message());
        assertThat(service.run(55, run.id()).taskId()).isNull();
        UUID task = UUID.randomUUID();
        repository.reconcile(second, task, "QUEUED", null, false, false, NOW.plusSeconds(150), NOW.plusSeconds(121));
        assertThat(service.run(55, run.id()).taskId()).isEqualTo(task);
    }

    @Test
    void validWorkerPublishesOnceAndContentIsOwnerScoped() {
        DailyRun run = queued();
        var grant = grant(run, UUID.randomUUID(), 1);
        when(scheduler.authorize(any())).thenReturn(grant);
        var result = service.generate(run.id(), proof());
        assertThat(result.status()).isEqualTo("READY");
        assertThat(service.generate(run.id(), proof()).editionId()).isEqualTo(result.editionId());
        assertThat(editions.edition(55, result.editionId()).zone()).isEqualTo("Asia/Shanghai");
        assertThatThrownBy(() -> editions.edition(56, result.editionId())).isInstanceOf(NewsException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition", Integer.class)).isEqualTo(1);
        assertThat(service.run(55, run.id()).taskId()).isEqualTo(grant.taskId());
    }

    @Test
    void leaseLostDuringBuildLeavesNoPublishedEditionButRetainsRegionBinding() {
        DailyRun run = queued();
        when(scheduler.authorize(any())).thenReturn(grant(run, UUID.randomUUID(), 1)).thenThrow(new NewsSchedulerClient.Failure(true));
        assertThatThrownBy(() -> service.generate(run.id(), proof())).isInstanceOf(NewsSchedulerClient.Failure.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_snapshot", Integer.class)).isEqualTo(1);
        assertThat(service.run(55, run.id()).editionId()).isNull();
    }

    @Test
    void oldFenceWrongNodeBusinessAndExpiredLeaseCannotPublish() {
        DailyRun run = queued(); UUID task = UUID.randomUUID();
        repository.accept(run.id(), grant(run, task, 2), NOW);
        List<ExecutionGrant> invalid = List.of(grant(run, task, 1),
                new ExecutionGrant(task, run.id().toString(), "news:" + run.id(), "other-node", 3, NOW.plusSeconds(90)),
                new ExecutionGrant(task, UUID.randomUUID().toString(), "news:" + run.id(), run.nodeId(), 3, NOW.plusSeconds(90)),
                new ExecutionGrant(task, run.id().toString(), "news:" + run.id(), run.nodeId(), 3, NOW.plusSeconds(5)));
        for (var item : invalid) {
            when(scheduler.authorize(any())).thenReturn(item);
            assertThatThrownBy(() -> service.generate(run.id(), proof())).hasMessage(ErrorCode.NEWS_016.message());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition", Integer.class)).isZero();
    }

    @Test
    void disablingBetweenBuildAndPublicationStopsPublication() {
        DailyRun run = queued(); var grant = grant(run, UUID.randomUUID(), 1);
        when(scheduler.authorize(any())).thenReturn(grant);
        repository.accept(run.id(), grant, NOW);
        service.change(55, change(1, false));
        assertThatThrownBy(() -> service.generate(run.id(), proof())).hasMessage(ErrorCode.NEWS_016.message());
        assertThat(service.run(55, run.id()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void successfulSchedulerTaskWithoutEditionIsAnExplicitFailure() {
        DailyRun run = queued();
        when(scheduler.create(any())).thenReturn(new NewsSchedulerClient.TaskState(UUID.randomUUID(), "SUCCEEDED"));
        service.relay();
        assertThat(service.run(55, run.id()).status()).isEqualTo("FAILED");
        assertThat(service.run(55, run.id()).errorCode()).isEqualTo("NEWS_019");
        assertThat(outbox(run.id())).isEqualTo("DONE");
    }

    @Test
    void workerEndpointsRejectUserAndAdminTokensAndMissingLease() throws Exception {
        DailyRun run = queued();
        for (String token : List.of("test-admin", "test-proxy")) {
            mvc.perform(post("/internal/jobs/news/runs/" + run.id() + "/generate").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/internal/jobs/news/runs/" + run.id() + "/generate").header("Authorization", "Bearer test-job-token-at-least-thirty-two-characters"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v1/news/subscription?ownerId=55").header("Authorization", "Bearer test-proxy"))
                .andExpect(jsonPath("$.schedulingEnabled").value(true)).andExpect(jsonPath("$.deliveryEnabled").value(false));
    }

    @Test
    void permanentRejectionCanBeRedrivenOnlyWithOriginalIdentity() {
        DailyRun run = queued(); UUID task = UUID.randomUUID();
        when(scheduler.create(any())).thenThrow(new NewsSchedulerClient.Failure(true)).thenReturn(new NewsSchedulerClient.TaskState(task, "QUEUED"));
        service.relay();
        assertThat(outbox(run.id())).isEqualTo("DEAD");
        repository.redrive(run.id(), NOW);
        service.relay();
        assertThat(service.run(55, run.id()).status()).isEqualTo("DISPATCHED");
        assertThat(service.runs(55, 30)).hasSize(1);
        assertThat(service.run(55, run.id()).taskId()).isEqualTo(task);
        assertThatThrownBy(() -> repository.redrive(run.id(), NOW)).isInstanceOf(NewsException.class);
    }

    @Test
    void failedPublicationRollsBackEditionAndReadyState() {
        DailyRun run = queued(); var grant = grant(run, UUID.randomUUID(), 1);
        repository.accept(run.id(), grant, NOW);
        var draft = editions.prepareScheduled(55, run.id(), run.cutoff(), ZoneId.of(run.zoneId()));
        assertThatThrownBy(() -> repository.publish(run.id(), grant, () -> grant, () -> {
            editions.saveScheduled(draft, run.id());
            throw new IllegalStateException("simulated database failure");
        }, clock::instant)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition", Integer.class)).isZero();
        assertThat(service.run(55, run.id()).editionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void nodeConfigurationChangeCannotSubstituteAnotherNodesRegion() {
        service.change(55, change(0, true));
        repository.scan(NOW, "previous-node");
        DailyRun run = service.runs(55, 30).getFirst();
        assertThatThrownBy(() -> service.generate(run.id(), proof())).hasMessage(ErrorCode.NEWS_016.message());
        verifyNoInteractions(scheduler);
    }

    @Test
    void callbackBeforeCreateResponseRetainsReadyWithoutFalseMissingEditionError() {
        DailyRun run = queued(); UUID task = UUID.randomUUID();
        when(scheduler.authorize(any())).thenReturn(grant(run, task, 1));
        when(scheduler.create(any())).thenAnswer(call -> {
            service.generate(run.id(), proof());
            return new NewsSchedulerClient.TaskState(task, "SUCCEEDED");
        });
        service.relay();
        assertThat(service.run(55, run.id()).status()).isEqualTo("READY");
        assertThat(service.run(55, run.id()).errorCode()).isNull();
        assertThat(outbox(run.id())).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT error_code FROM news_task_outbox WHERE run_id = ?", String.class, run.id().toString())).isNull();
    }

    private String outbox(UUID id) { return jdbc.queryForObject("SELECT state FROM news_task_outbox WHERE run_id = ?", String.class, id.toString()); }
    private SubscriptionChange change(long revision, boolean enabled) { return new SubscriptionChange(revision, enabled, "Asia/Shanghai", "08:00"); }
    private DailyRun queued() { service.change(55, change(0, true)); service.scan(); return service.runs(55, 30).getFirst(); }
    private ExecutionProof proof() { return new ExecutionProof(UUID.randomUUID(), UUID.randomUUID()); }
    private ExecutionGrant grant(DailyRun run, UUID task, long fence) { return new ExecutionGrant(task, run.id().toString(), "news:" + run.id(), run.nodeId(), fence, clock.instant().plusSeconds(90)); }

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        /** 返回测试时钟时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 固定测试时钟使用协调世界时。 */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 返回当前可控时刻。 */
        @Override public Instant instant() { return now.get(); }
    }
}
