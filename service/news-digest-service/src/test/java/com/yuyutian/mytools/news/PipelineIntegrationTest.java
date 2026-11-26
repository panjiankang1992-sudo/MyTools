package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.Model.RegionModels.*;
import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.Model.PipelineModels.*;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.mapper.*;
import com.yuyutian.mytools.news.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:news-pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "news.internal-token=test-proxy", "news.admin-token=test-admin", "news.region.node-id=node",
        "news.region.report-token=test-region-token-at-least-thirty-two-characters",
        "news.collection-enabled=true", "news.pipeline.enabled=true", "news.pipeline.timer-enabled=false",
        "news.scheduling.enabled=true", "news.scheduling.timer-enabled=false",
        "news.scheduling.business-token=test-scheduler-token-at-least-thirty-two-characters",
        "news.scheduling.worker-token=test-job-token-at-least-thirty-two-characters"
})
@AutoConfigureMockMvc
class PipelineIntegrationTest {
    private static final Instant NOW = Fixtures.CUTOFF;
    @Autowired private SourcePollingService polling;
    @Autowired private SourcePollingRepository polls;
    @Autowired private NewsRepository news;
    @Autowired private RegionJobService regions;
    @Autowired private RegionJobRepository jobs;
    @Autowired private RegionRepository observations;
    @Autowired private RegionService regionService;
    @Autowired private SchedulingRepository queue;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @MockitoBean private FeedTransport transport;
    @MockitoBean private NewsSchedulerClient scheduler;

    @BeforeEach void resetState() {
        for (String table : List.of("news_region_observation", "news_region_job", "news_region_schedule", "news_source_poll", "news_task_outbox", "news_article", "news_source", "news_provider_cooldown")) jdbc.update("DELETE FROM " + table);
        Fixtures.resetRegistry(jdbc);
        clock.now.set(NOW); reset(transport, scheduler);
        when(transport.fetch(any())).thenReturn(feed());
        when(transport.fetchPage(argThat(source -> source != null && source.spec().feedUrl().equals("https://example.com/fixture"))))
                .thenReturn(new FeedTransport.FeedResponse(200, ("<article><p>" + Fixtures.EVIDENCE + "</p></article>").getBytes(StandardCharsets.UTF_8), null, null));
    }

    @Test void realCollectorPersistsEvidenceAndRssWaitsThirtyMinutes() {
        news.saveSource(Fixtures.source("rss", Category.WORLD));
        assertThat(polling.poll()).isEqualTo(1);
        assertThat(news.candidates(NOW.plusMillis(1))).hasSize(1);
        assertThat(news.candidates(NOW.plusMillis(1)).getFirst().evidenceText()).isEqualTo(Fixtures.EVIDENCE.strip());
        assertThat(polls.states().getFirst().nextDueAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(polling.poll()).isZero();
        clock.now.set(NOW.plusSeconds(1800));
        assertThat(polling.poll()).isEqualTo(1);
        assertThat(news.candidates(clock.instant().plusMillis(1))).hasSize(1);
        verify(transport, times(2)).fetch(argThat(source -> source.spec().feedUrl().endsWith(".xml")));
        verify(transport, times(2)).fetchPage(argThat(source -> source.spec().feedUrl().equals("https://example.com/fixture")));
    }

    @Test void hotBoardEntersObservedWindowWithoutFabricatingNewsDate() {
        String url = "https://top.baidu.com/board?tab=realtime";
        news.saveSource(new SourceSpec("hot", "Hot board", url, Category.TRENDING, "baidu", null,
                EvidencePolicy.EXCERPT_ALLOWED, url, 0.5, true, SourceKind.BAIDU_HOT));
        String body = "<!--s-data:{\"data\":{\"cards\":[{\"component\":\"hotList\",\"content\":[{\"word\":\"Trend topic\",\"desc\":\"Platform facts\",\"hotScore\":\"100\",\"rawUrl\":\"https://www.baidu.com/s?wd=topic\"}]}]}}-->";
        when(transport.fetch(any())).thenReturn(new FeedTransport.FeedResponse(200, body.getBytes(StandardCharsets.UTF_8), null, null));
        assertThat(polling.poll()).isEqualTo(1);
        var candidates = news.candidates(NOW.plusMillis(1));
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().publishedAt()).isNull();
        assertThat(candidates.getFirst().source().category()).isEqualTo(Category.TRENDING);
        verify(transport, never()).fetchPage(any());
    }

    @Test void failureBackoffAndRateLimitDoNotStopOtherSources() {
        news.saveSource(Fixtures.source("a", Category.WORLD));
        news.saveSource(Fixtures.source("b", Category.WORLD));
        when(transport.fetch(any())).thenThrow(new NewsException(ErrorCode.NEWS_006)).thenReturn(feed());
        assertThat(polling.poll()).isEqualTo(2);
        var failed = polls.states().stream().filter(p -> p.sourceId().equals("a")).findFirst().orElseThrow();
        assertThat(failed.failures()).isEqualTo(1);
        assertThat(failed.nextDueAt()).isEqualTo(NOW.plusSeconds(60));
        clock.now.set(NOW.plusSeconds(60));
        when(transport.fetch(any())).thenThrow(new SourceRateLimitException(NOW.plusSeconds(3600)));
        polling.poll();
        assertThat(polls.states().stream().filter(p -> p.sourceId().equals("a")).findFirst().orElseThrow().nextDueAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test void parallelPollersOnlyFetchOneSourceOnce() throws Exception {
        news.saveSource(Fixtures.source("rss", Category.WORLD));
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(polling::poll));
            for (var future : futures) future.get();
        }
        verify(transport).fetch(argThat(source -> source.spec().feedUrl().endsWith(".xml")));
        verify(transport).fetchPage(argThat(source -> source.spec().feedUrl().equals("https://example.com/fixture")));
    }

    @Test void pollingLeaseCannotOverwriteNewClaimAndReservationSurvivesCrash() {
        news.saveSource(Fixtures.source("rss", Category.WORLD)); polls.register("rss", NOW);
        var first = polls.claim(NOW).orElseThrow();
        polls.reserve(first, NOW.plusSeconds(1800), NOW);
        assertThat(polls.claim(NOW.plusSeconds(181))).isEmpty();
        var second = polls.claim(NOW.plusSeconds(1800)).orElseThrow();
        assertThat(polls.finish(first, NOW.plusSeconds(1801), NOW.plusSeconds(9999), "STALE", 9)).isFalse();
        assertThat(polls.finish(second, NOW.plusSeconds(1801), NOW.plusSeconds(3601), "FRESH", 0)).isTrue();
    }

    @Test void disabledSourcesStayClosedAndTickHasFiveSourceBudget() {
        for (int i = 0; i < 7; i++) news.saveSource(Fixtures.source("rss" + i, Category.WORLD));
        assertThat(polling.poll()).isEqualTo(5);
        assertThat(polling.poll()).isEqualTo(2);
        var original = Fixtures.source("disabled", Category.WORLD);
        news.saveSource(new SourceSpec(original.id(), original.name(), original.feedUrl(), original.category(), original.publisherKey(), null,
                original.evidencePolicy(), original.rightsReference(), original.authority(), false));
        assertThat(polling.poll()).isZero();
        verify(transport, times(7)).fetch(argThat(source -> source.spec().feedUrl().endsWith(".xml")));
        verify(transport, times(7)).fetchPage(argThat(source -> source.spec().feedUrl().equals("https://example.com/fixture")));
    }

    @Test void concurrentRegionScanOnlyCreatesOneJobAndEditionQueueCannotConsumeIt() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<Optional<UUID>>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(pool.submit(regions::scan));
            for (var result : results) result.get();
        }
        assertThat(jobs.jobs()).hasSize(1);
        assertThat(queue.claim(NOW)).isEmpty();
        assertThat(queue.claim(NOW, "REGION")).isPresent();
        clock.now.set(NOW.plusSeconds(21600));
        assertThat(regions.scan()).isEmpty();
        jobs.cancel(jobs.jobs().getFirst().id(), clock.instant());
        assertThat(regions.scan()).isPresent();
    }

    @Test void acceptedRegionReportAndJobAreAtomicAndRetriesReturnOriginalReceipt() {
        RegionJob job = queued(); var proof = proof(); var report = report(proof.executionId());
        when(scheduler.authorize(any())).thenReturn(grant(job, UUID.randomUUID(), 1));
        assertThat(regions.receipt(job.id(), proof)).isEmpty();
        var receipt = regions.observe(job.id(), proof, report);
        assertThat(receipt.status()).isEqualTo("STORED");
        assertThat(observations.observation(report.id())).contains(report);
        assertThat(regions.receipt(job.id(), proof()).orElseThrow()).isEqualTo(receipt);
        assertThat(regions.observe(job.id(), proof, report)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_observation", Integer.class)).isEqualTo(1);
    }

    @Test void oldFenceWrongNodeWrongDomainAndMismatchedReportCannotCommit() {
        RegionJob job = queued(); var proof = proof(); var report = report(proof.executionId()); UUID task = UUID.randomUUID();
        var invalid = List.of(new ExecutionGrant(task, job.id().toString(), "news:" + job.id(), "node", 1, NOW.plusSeconds(90)),
                new ExecutionGrant(task, job.id().toString(), "news:region:" + job.id(), "other", 1, NOW.plusSeconds(90)),
                new ExecutionGrant(task, job.id().toString(), "news:region:" + job.id(), "node", 1, NOW.plusSeconds(5)));
        for (var value : invalid) {
            when(scheduler.authorize(any())).thenReturn(value);
            assertThatThrownBy(() -> regions.observe(job.id(), proof, report)).hasMessage(ErrorCode.NEWS_016.message());
        }
        when(scheduler.authorize(any())).thenReturn(grant(job, task, 1));
        assertThatThrownBy(() -> regions.observe(job.id(), proof(), report)).hasMessage(ErrorCode.NEWS_013.message());
        jdbc.update("UPDATE news_region_job SET last_fence = 2 WHERE id = ?", job.id().toString());
        assertThatThrownBy(() -> regions.observe(job.id(), proof, report)).hasMessage(ErrorCode.NEWS_016.message());
        assertThat(observations.observation(report.id())).isEmpty();
    }

    @Test void cancellationOrAuthorizationLossLeavesNoReport() {
        RegionJob job = queued(); var proof = proof(); var report = report(proof.executionId());
        when(scheduler.authorize(any())).thenReturn(grant(job, UUID.randomUUID(), 1)).thenThrow(new NewsSchedulerClient.Failure(false));
        assertThatThrownBy(() -> regions.observe(job.id(), proof, report)).isInstanceOf(NewsSchedulerClient.Failure.class);
        assertThat(observations.observation(report.id())).isEmpty();
        doReturn(grant(job, UUID.randomUUID(), 1)).when(scheduler).authorize(any());
        jobs.cancel(job.id(), NOW);
        assertThatThrownBy(() -> regions.observe(job.id(), proof, report)).hasMessage(ErrorCode.NEWS_016.message());
    }

    @Test void lostCreationResponseUsesSameJobAndCancelledUnknownTaskIsReconciled() {
        RegionJob job = queued(); UUID task = UUID.randomUUID();
        when(scheduler.createRegion(any())).thenThrow(new NewsSchedulerClient.Failure(false)).thenReturn(new NewsSchedulerClient.TaskState(task, "QUEUED"));
        regions.relay(); jobs.cancel(job.id(), NOW);
        clock.now.set(NOW.plusSeconds(5)); regions.relay();
        when(scheduler.cancelRegion(any())).thenReturn(new NewsSchedulerClient.TaskState(task, "CANCELLED"));
        clock.now.set(NOW.plusSeconds(6)); regions.relay();
        assertThat(jobs.job(job.id()).taskId()).isEqualTo(task);
        assertThat(jobs.job(job.id()).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT state FROM news_task_outbox WHERE run_id = ?", String.class, job.id().toString())).isEqualTo("DONE");
        assertThat(jobs.jobs()).hasSize(1);
    }

    @Test void missingReportAndPermanentFailuresAreVisibleAndRedriveKeepsIdentity() {
        RegionJob job = queued(); UUID task = UUID.randomUUID();
        when(scheduler.createRegion(any())).thenThrow(new NewsSchedulerClient.Failure(true)).thenReturn(new NewsSchedulerClient.TaskState(task, "SUCCEEDED"));
        regions.relay();
        assertThat(jobs.job(job.id()).errorCode()).isEqualTo("NEWS_017");
        jobs.redrive(job.id(), NOW); regions.relay();
        assertThat(jobs.job(job.id()).errorCode()).isEqualTo("NEWS_020");
        assertThat(jobs.job(job.id()).status()).isEqualTo("FAILED");
    }

    @Test void staleQueueLeaseAndExpiredProbeCannotPublish() {
        RegionJob job = queued(); var claim = queue.claim(NOW, "REGION").orElseThrow();
        assertThatThrownBy(() -> jobs.reconcile(claim, UUID.randomUUID(), "RUNNING", null, false, false, NOW, NOW.plusSeconds(121))).hasMessage(ErrorCode.NEWS_016.message());
        assertThat(jobs.job(job.id()).taskId()).isNull();
        clock.now.set(NOW.plusSeconds(900)); var proof = proof();
        when(scheduler.authorize(any())).thenReturn(grant(job, UUID.randomUUID(), 1));
        assertThatThrownBy(() -> regions.observe(job.id(), proof, report(proof.executionId()))).hasMessage(ErrorCode.NEWS_016.message());
    }

    @Test void pipelineClosesLegacyReportAndKeepsAdminAndWorkerScopesSeparate() throws Exception {
        var report = report(UUID.randomUUID());
        mvc.perform(post("/internal/worker/news/region-observations").header("Authorization", "Bearer test-region-token-at-least-thirty-two-characters")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(report))).andExpect(status().isConflict());
        RegionJob job = queued();
        mvc.perform(post("/internal/jobs/news/region-jobs/" + job.id() + "/receipt").header("Authorization", "Bearer test-proxy")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/admin/news/pipeline/sources").header("Authorization", "Bearer test-proxy")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/admin/news/pipeline/region-jobs").header("Authorization", "Bearer test-admin")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].egressFingerprint").doesNotExist()).andExpect(jsonPath("$[0].leaseToken").doesNotExist());
    }

    @Test void observationAndJobCompletionRollBackTogether() {
        RegionJob job = queued(); var proof = proof(); var report = report(proof.executionId()); var grant = grant(job, UUID.randomUUID(), 1);
        assertThatThrownBy(() -> jobs.complete(job.id(), grant, report, () -> grant, () -> {
            regionService.observe(report);
            throw new IllegalStateException("simulated completion failure");
        }, clock::instant)).isInstanceOf(IllegalStateException.class);
        assertThat(observations.observation(report.id())).isEmpty();
        assertThat(jobs.job(job.id()).observationId()).isNull();
    }

    @Test void completedCallbackBeforeCreationResponseDoesNotBecomeMissingReportFailure() {
        RegionJob job = queued(); var proof = proof(); UUID task = UUID.randomUUID();
        when(scheduler.authorize(any())).thenReturn(grant(job, task, 1));
        when(scheduler.createRegion(any())).thenAnswer(call -> {
            regions.observe(job.id(), proof, report(proof.executionId()));
            return new NewsSchedulerClient.TaskState(task, "SUCCEEDED");
        });
        regions.relay();
        assertThat(jobs.job(job.id()).status()).isEqualTo("STORED");
        assertThat(jobs.job(job.id()).errorCode()).isNull();
        assertThat(jdbc.queryForObject("SELECT error_code FROM news_task_outbox WHERE run_id = ?", String.class, job.id().toString())).isNull();
    }

    private RegionJob queued() { return jobs.job(regions.scan().orElseThrow()); }
    private ExecutionProof proof() { return new ExecutionProof(UUID.randomUUID(), UUID.randomUUID()); }
    private ExecutionGrant grant(RegionJob job, UUID task, long fence) { return new ExecutionGrant(task, job.id().toString(), "news:region:" + job.id(), "node", fence, clock.instant().plusSeconds(90)); }
    private RegionObservation report(UUID execution) { return new RegionObservation(execution, "node", clock.instant(), ObservationStatus.RESOLVED,
            "a".repeat(64), "IPV4", "DIRECT", "MAXMIND_MMDB", "fixture", new GeoEvidence("CN", "SC", 123L, "Fixture", 20)); }
    private FeedTransport.FeedResponse feed() {
        String xml = "<rss version='2.0'><channel><item><title>Offline pipeline test event</title><link>https://example.com/fixture</link><pubDate>Sat, 17 Oct 2026 23:00:00 GMT</pubDate><description>Fixture text</description></item></channel></rss>";
        return new FeedTransport.FeedResponse(200, xml.getBytes(StandardCharsets.UTF_8), "fixture-etag", null);
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock testClock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        /** 返回固定时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 保持测试使用统一时区。 */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 返回可控测试时间。 */
        @Override public Instant instant() { return now.get(); }
    }
}
