package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.news.Model.NewsModels.Category;
import com.yuyutian.mytools.news.Model.SchedulingModels.ExecutionGrant;
import com.yuyutian.mytools.news.Model.SchedulingModels.ExecutionProof;
import com.yuyutian.mytools.news.Model.WeeklyModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.mapper.WeeklyRepository;
import com.yuyutian.mytools.news.service.NewsSchedulerClient;
import com.yuyutian.mytools.news.service.WeeklyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:weekly-integration;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "news.internal-token=weekly-proxy-token","news.admin-token=weekly-admin-token",
        "news.weekly.enabled=true","news.weekly.timer-enabled=false","news.scheduling.enabled=false",
        "news.region.node-id=weekly-node","news.scheduling.business-token=weekly-business-token-12345678901234567890",
        "news.scheduling.worker-token=weekly-worker-token-12345678901234567890"
})
@AutoConfigureMockMvc
class WeeklyIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-11-22T06:30:00Z");
    @Autowired private WeeklyRepository repository;
    @Autowired private WeeklyService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private MockMvc mvc;
    @Autowired private TestClock clock;
    @MockitoBean private NewsSchedulerClient scheduler;
    private static final java.nio.file.Path ASSET_ROOT=assetRoot();
    @DynamicPropertySource static void assets(DynamicPropertyRegistry registry) {
        registry.add("news.weekly.asset-directory",ASSET_ROOT::toString);
    }
    private static java.nio.file.Path assetRoot() {
        try { return java.nio.file.Files.createTempDirectory("weekly-assets-integration-"); }
        catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }

    @org.junit.jupiter.api.AfterAll static void cleanupAssets() throws Exception {
        try (var paths=java.nio.file.Files.walk(ASSET_ROOT)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
        }
    }

    @Test void originalImageMustBelongToPublishedReportAndCurrentOwner() throws Exception {
        View view=service.create(1,new Create("click-image")); Job job=repository.owned(1,view.id()); grant(job,1);
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        String asset=service.upload(job.dispatchId(),proof(),bytes.toByteArray()).get("assetId");
        assertThatThrownBy(() -> service.image(1,job.id(),asset)).isInstanceOf(NewsException.class);
        ObjectNode payload=report(job,false);
        ((ObjectNode)payload.path("sections").get(0).path("items").get(0)).putObject("image")
                .put("assetId",asset).put("caption","Source image").put("sourceUrl","https://example.org/news");
        service.publish(job.dispatchId(),proof(),payload);
        assertThat(service.image(1,job.id(),asset).mime()).isEqualTo("image/png");
        assertThatThrownBy(() -> service.image(2,job.id(),asset)).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> service.image(1,job.id(),"a".repeat(64))).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> service.upload(job.dispatchId(),proof(),bytes.toByteArray())).isInstanceOf(NewsException.class);
    }

    @BeforeEach void reset() {
        for (String table : List.of("news_weekly_report","news_weekly_request","news_weekly_job")) jdbc.update("DELETE FROM " + table);
        clock.now.set(NOW);
    }

    @Test void concurrentClicksAndReplayedAliasesKeepOnePersistentJob() throws Exception {
        try (var executor=Executors.newFixedThreadPool(4)) {
            List<Callable<View>> calls=java.util.stream.IntStream.range(0,8).mapToObj(index ->
                    (Callable<View>)() -> service.create(1,new Create("click-key-"+index))).toList();
            var futures=executor.invokeAll(calls);
            UUID first=futures.getFirst().get().id();
            for (var future : futures) assertThat(future.get().id()).isEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_job",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_request",Integer.class)).isEqualTo(8);
            // 新仓储实例读取同一数据库，证明不是内存 Future 充当任务状态。
            var restarted=new WeeklyRepository(jdbc,json,manager);
            assertThat(restarted.active(1).orElseThrow().id()).isEqualTo(first);
            assertThat(restarted.create(1,"click-key-7","weekly-node",NOW.plusSeconds(400)).id()).isEqualTo(first);
        }
    }

    @Test void longRunningCompletionHasFiveMinuteReuseWithoutExtendingTheWindow() {
        Job job=repository.owned(1,service.create(1,new Create("long-task")).id());
        clock.now.set(NOW.plusSeconds(900));
        grant(job,1);
        service.publish(job.dispatchId(),proof(),report(job,false));
        clock.now.set(NOW.plusSeconds(1199));
        View reused=service.create(1,new Create("after-completion"));
        assertThat(reused.id()).isEqualTo(job.id());
        assertThat(reused.reused()).isTrue();
        assertThat(service.create(2,new Create("another-owner")).id()).isNotEqualTo(job.id());
        clock.now.set(NOW.plusSeconds(1201));
        View fresh=service.create(1,new Create("after-cooldown"));
        assertThat(fresh.id()).isNotEqualTo(job.id());
        assertThat(fresh.windowEnd()).isEqualTo(clock.instant());
        assertThat(service.create(1,new Create("after-completion")).id()).isEqualTo(job.id());
    }

    @Test void authOwnerAndFrozenWindowCannotBeOverriddenByTheApp() throws Exception {
        mvc.perform(post("/internal/v1/news/weekly-jobs?ownerId=1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"click-test\"}")).andExpect(status().isUnauthorized());
        for (String injected : List.of("\"ownerId\":2", "\"cutoff\":\"2026-01-01T00:00:00Z\"", "\"command\":\"x\"")) {
            mvc.perform(post("/internal/v1/news/weekly-jobs?ownerId=1").header("Authorization","Bearer weekly-proxy-token")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"click-test\","+injected+"}"))
                    .andExpect(status().isBadRequest());
        }
        View job=service.create(1,new Create("click-owner"));
        assertThat(job.windowEnd()).isEqualTo(NOW);
        assertThat(job.windowStart()).isEqualTo(NOW.minus(Duration.ofDays(7)));
        mvc.perform(get("/internal/v1/news/weekly-jobs/"+job.id()+"?ownerId=2")
                .header("Authorization","Bearer weekly-proxy-token")).andExpect(status().isNotFound());
        verifyNoInteractions(scheduler);
    }

    @Test void publishIsAtomicIdempotentAndDetailsAreOwnerScoped() throws Exception {
        View view=service.create(1,new Create("publish-test"));
        Job job=repository.owned(1,view.id());
        grant(job,2);
        Context done=service.publish(job.dispatchId(),proof(),report(job,false));
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(service.publish(job.dispatchId(),proof(),report(job,false)).reportRevision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_report",Integer.class)).isEqualTo(1);
        assertThat(service.report(1,job.id()).path("sections").get(0).has("items")).isFalse();
        assertThat(service.article(1,job.id(),"event-1").path("points").size()).isEqualTo(1);
        assertThatThrownBy(() -> service.article(2,job.id(),"event-1")).isInstanceOf(NewsException.class);
        mvc.perform(get("/internal/v1/news/weekly-reports/"+job.id()+"?ownerId=1").header("Authorization","Bearer weekly-proxy-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.articleCount").value(1));
    }

    @Test void failedCategoryRetryKeepsWindowButRevokesOldDispatchIdentity() {
        Job job=repository.owned(1,service.create(1,new Create("partial-test")).id());
        grant(job,4);
        service.publish(job.dispatchId(),proof(),report(job,true));
        View partial=service.job(1,job.id());
        clock.now.set(NOW.plusSeconds(60));
        View retried=service.retry(1,job.id(),new Retry("retry-test-key",partial.revision()));
        Job next=repository.owned(1,job.id());
        assertThat(next.dispatchId()).isNotEqualTo(job.dispatchId());
        assertThat(retried.windowEnd()).isEqualTo(NOW);
        assertThat(retried.reportId()).isEqualTo(job.id());
        assertThatThrownBy(() -> service.context(job.dispatchId(),proof())).isInstanceOf(NewsException.class);
        assertThat(service.retry(1,job.id(),new Retry("retry-test-key",partial.revision())).attempt()).isEqualTo(2);
        assertThatThrownBy(() -> service.retry(1,job.id(),new Retry("another-retry",partial.revision())))
                .isInstanceOf(NewsException.class);
        grant(next,1);
        service.publish(next.dispatchId(),proof(),report(next,false));
        assertThat(service.history(1,null,20).items()).hasSize(1);
        assertThat(service.report(1,job.id()).path("revision").asLong()).isEqualTo(2);
    }

    @Test void oldFencesAndExpiredLeasesCannotCommitEvenIfPayloadIsValid() {
        Job job=repository.owned(1,service.create(1,new Create("fence-test")).id());
        grant(job,4);
        service.context(job.dispatchId(),proof());
        grant(job,3);
        assertThatThrownBy(() -> service.publish(job.dispatchId(),proof(),report(job,false)))
                .isInstanceOfSatisfying(NewsException.class,error -> assertThat(error.code()).isEqualTo(ErrorCode.NEWS_016));
        grant(job,5);
        clock.now.set(NOW.plusSeconds(301));
        assertThatThrownBy(() -> service.publish(job.dispatchId(),proof(),report(job,false))).isInstanceOf(NewsException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_report",Integer.class)).isZero();
    }

    @Test void badWindowDuplicateEventsAndMissingDetailsAreNotPublished() {
        Job job=repository.owned(1,service.create(1,new Create("invalid-report")).id());
        grant(job,1);
        ObjectNode bad=report(job,false); bad.put("windowStart",NOW.toString());
        assertThatThrownBy(() -> service.publish(job.dispatchId(),proof(),bad)).isInstanceOf(NewsException.class);
        ObjectNode duplicate=report(job,false);
        ((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("sections").get(1).path("items"))
                .add(duplicate.path("sections").get(0).path("items").get(0).deepCopy());
        assertThatThrownBy(() -> service.publish(job.dispatchId(),proof(),duplicate)).isInstanceOf(NewsException.class);
        ObjectNode empty=report(job,false);
        ((ObjectNode)empty.path("sections").get(0).path("items").get(0)).put("summary","headline only");
        assertThatThrownBy(() -> service.publish(job.dispatchId(),proof(),empty)).isInstanceOf(NewsException.class);
        assertThat(repository.owned(1,job.id()).status()).isEqualTo("QUEUED");
    }

    @Test void dispatchLeaseCanRecoverAndOldClaimCannotOverwriteNewClaim() {
        Job job=repository.owned(1,service.create(1,new Create("dispatch-test")).id());
        Claim old=repository.claim(NOW).orElseThrow();
        assertThat(repository.claim(NOW.plusSeconds(10))).isEmpty();
        Claim next=repository.claim(NOW.plusSeconds(91)).orElseThrow();
        repository.reconcile(old,new NewsSchedulerClient.TaskState(UUID.randomUUID(),"FAILED"),null,false,NOW.plusSeconds(92));
        assertThat(repository.owned(1,job.id()).status()).isEqualTo("QUEUED");
        repository.reconcile(next,new NewsSchedulerClient.TaskState(UUID.randomUUID(),"RUNNING"),null,false,NOW.plusSeconds(92));
        assertThat(repository.owned(1,job.id()).status()).isEqualTo("RUNNING");
    }

    @Test void taskTimesOutEvenWhenSchedulerCannotBeReached() {
        Job job=repository.owned(1,service.create(1,new Create("timeout-test")).id());
        Instant expired=NOW.plus(Duration.ofHours(3));
        Claim claim=repository.claim(expired).orElseThrow();
        repository.reconcile(claim,null,ErrorCode.NEWS_015.name(),false,expired);
        assertThat(repository.owned(1,job.id()).status()).isEqualTo("FAILED");
        assertThat(repository.owned(1,job.id()).errorCode()).isEqualTo("NEWS_036");
        assertThat(repository.active(1)).isEmpty();
    }

    @Test void retentionRemovesBodiesButKeepsClickKeysAsTombstones() {
        View view=service.create(1,new Create("click-retained")); Job job=repository.owned(1,view.id()); grant(job,1);
        service.publish(job.dispatchId(),proof(),report(job,false));
        clock.now.set(NOW.plus(Duration.ofDays(91)));
        service.cleanup(); service.cleanup();
        assertThat(service.history(1,null,20).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_report",Integer.class)).isZero();
        assertThatThrownBy(() -> service.report(1,job.id())).isInstanceOfSatisfying(NewsException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.NEWS_026));
        assertThatThrownBy(() -> service.create(1,new Create("click-retained"))).isInstanceOf(NewsException.class);
        verify(scheduler,times(1)).authorize(any());
    }

    private ExecutionProof proof() { return new ExecutionProof(UUID.randomUUID(),UUID.randomUUID()); }
    private void grant(Job job,long fence) {
        UUID task=job.taskId()==null ? UUID.nameUUIDFromBytes(job.dispatchId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) : job.taskId();
        when(scheduler.authorize(any())).thenReturn(new ExecutionGrant(task,job.dispatchId().toString(),"news:weekly:"+job.dispatchId(),
                job.nodeId(),fence,clock.instant().plusSeconds(300)));
    }
    private ObjectNode report(Job job,boolean partial) {
        ObjectNode report=json.createObjectNode();
        report.put("windowStart",job.windowStart().toString());report.put("windowEnd",job.windowEnd().toString());
        report.put("status",partial?"PARTIAL":"SUCCEEDED");report.put("rulesVersion","test-only");
        var sections=report.putArray("sections");var failures=report.putArray("failedSections");
        for (Category category : Category.values()) {
            if (partial && category==Category.MILITARY) { failures.addObject().put("category",category.name()).put("code","DSH_TIMEOUT");continue; }
            var section=sections.addObject().put("category",category.name());var items=section.putArray("items");
            if (category!=Category.TECH) continue;
            var item=items.addObject().put("rank",1).put("eventId","event-1").put("title","Offline fixture news")
                    .put("summary","A sufficiently detailed offline fixture summary with verified source facts, not real news.")
                    .put("rankingReason","Relevant fixture").put("limitation","").put("publishedAt",NOW.minusSeconds(20).toString());
            item.putArray("points").add("A source-supported fixture fact.");
            item.putArray("sources").add("fixture");item.putArray("sourceLinks").add("https://example.org/news");
        }
        return report;
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary TestClock weeklyClock() { return new TestClock(); }
    }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        /** 测试固定使用 UTC。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 时间来自可控原子引用，支持模拟重启和租约过期。 */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 获取当前测试时刻。 */
        @Override public Instant instant() { return now.get(); }
    }
}
