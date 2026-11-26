package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.service.CollectionService;
import com.yuyutian.mytools.news.service.EditionService;
import com.yuyutian.mytools.news.service.FeedTransport;
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
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static com.yuyutian.mytools.news.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:news-integration;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "news.internal-token=test-user-proxy-token", "news.admin-token=test-source-admin-token", "news.collection-enabled=true"
})
@AutoConfigureMockMvc
class NewsIntegrationTest {
    @Autowired private NewsRepository repository;
    @Autowired private EditionService editions;
    @Autowired private CollectionService collection;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private StubTransport transport;

    @BeforeEach
    void clearDatabase() {
        transport.responses.clear();
        jdbc.update("DELETE FROM news_edition");
        jdbc.update("DELETE FROM news_region_snapshot");
        Fixtures.resetControl(jdbc);
        Fixtures.resetRegistry(jdbc);
        jdbc.update("DELETE FROM news_article");
        jdbc.update("DELETE FROM news_source");
        Fixtures.resetRegistry(jdbc);
        jdbc.update("UPDATE news_collection_capacity SET article_rows=0,snapshot_rows=0,withdrawal_rows=0,payload_bytes=0 WHERE id=1");
    }

    @Test
    void routesRequireCorrectScopeAndOwnerCannotBeForgedInPayload() throws Exception {
        mvc.perform(get("/internal/v1/news/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/admin/news/sources").header("Authorization", "Bearer test-user-proxy-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/news/capabilities").header("Authorization", "Bearer test-source-admin-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/news/capabilities").header("Authorization", "Bearer test-user-proxy-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryEnabled").value(false));
        mvc.perform(post("/internal/v1/news/previews?ownerId=1").header("Authorization", "Bearer test-user-proxy-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":2,\"idempotencyKey\":\"a\",\"cutoff\":\"2026-10-18T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payloadValidationFailsBeforePersistence() throws Exception {
        mvc.perform(post("/internal/v1/news/previews?ownerId=0").header("Authorization", "Bearer test-user-proxy-token")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new PreviewRequest("", CUTOFF))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v1/news/editions?ownerId=1&limit=101").header("Authorization", "Bearer test-user-proxy-token"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v1/news/editions").header("Authorization", "Bearer test-user-proxy-token"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition", Integer.class)).isZero();
    }

    @Test
    void previewsAreImmutableIsolatedAndRejectChangedIdempotencyPayload() throws Exception {
        SourceSpec source = source("world", Category.WORLD);
        repository.saveSource(source);
        repository.ingest(source.id(), List.of(input("1")), CUTOFF.minusSeconds(1));
        Edition first = editions.preview(1, new PreviewRequest("same-key", CUTOFF));
        repository.ingest(source.id(), List.of(input("2")), CUTOFF.minusSeconds(1));
        assertThat(editions.preview(1, new PreviewRequest("same-key", CUTOFF))).isEqualTo(first);
        assertThat(editions.preview(2, new PreviewRequest("same-key", CUTOFF)).id()).isNotEqualTo(first.id());
        mvc.perform(get("/internal/v1/news/editions/" + first.id() + "?ownerId=2")
                        .header("Authorization", "Bearer test-user-proxy-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NEWS_003"));
        mvc.perform(post("/internal/v1/news/previews?ownerId=1").header("Authorization", "Bearer test-user-proxy-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PreviewRequest("same-key", CUTOFF.minusSeconds(1)))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEWS_004"));
        assertThat(editions.history(1, 30)).hasSize(1);
        assertThat(first.sections().get(Category.LOCAL).gaps()).contains("REGION_UNRESOLVED");
    }

    @Test
    void concurrentSameKeyProducesOneDurableEdition() throws Exception {
        var start = new CountDownLatch(1);
        List<Future<Edition>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> { start.await(); return editions.preview(1, new PreviewRequest("concurrent", CUTOFF)); }));
            }
            start.countDown();
            List<UUID> ids = new ArrayList<>();
            for (Future<Edition> future : futures) ids.add(future.get().id());
            assertThat(ids.stream().distinct()).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition", Integer.class)).isEqualTo(1);
    }

    @Test
    void ingestionDeduplicatesAndDoesNotStoreUnauthorizedEvidence() {
        SourceSpec restricted = new SourceSpec("restricted", "restricted", "https://example.com/rss", Category.WORLD,
                "publisher", null, EvidencePolicy.METADATA_ONLY, "test", 0.8, true);
        repository.saveSource(restricted);
        assertThat(repository.ingest("restricted", List.of(input("1")), CUTOFF.minusSeconds(1))).isEqualTo(1);
        assertThat(repository.ingest("restricted", List.of(input("1")), CUTOFF)).isZero();
        Article stored = repository.candidates(CUTOFF).getFirst();
        assertThat(stored.evidenceText()).isEmpty();
        assertThat(stored.fetchedAt()).isEqualTo(CUTOFF.minusSeconds(1));
        assertThatThrownBy(() -> repository.saveSource(source("restricted", Category.TECH))).isInstanceOf(NewsException.class);
    }

    @Test
    void ingestionFailureRollsBackEarlierRowsAndCorrectedDateHidesOldVersion() {
        repository.saveSource(source("world", Category.WORLD));
        assertThatThrownBy(() -> repository.ingest("world", List.of(input("1"), new ArticleInput("file:///etc/passwd", "invalid", CUTOFF, "", 0)), CUTOFF))
                .isInstanceOf(NewsException.class);
        assertThat(repository.candidates(CUTOFF)).isEmpty();
        repository.ingest("world", List.of(input("1")), CUTOFF.minusSeconds(1));
        repository.ingest("world", List.of(new ArticleInput(input("1").url(), input("1").title(), CUTOFF.minusSeconds(8 * 86400), EVIDENCE, 0)), CUTOFF);
        assertThat(repository.candidates(CUTOFF)).isEmpty();
    }

    @Test
    void collectionUpdatesHealthAndNotModifiedDoesNotRefreshArticlePublication() {
        repository.saveSource(source("world", Category.WORLD));
        byte[] feed = ("<rss><channel><item><title>Old dated report</title><link>https://example.com/a</link>"
                + "<pubDate>Fri, 16 Oct 2026 00:00:00 GMT</pubDate><description>Evidence</description></item></channel></rss>")
                .getBytes(StandardCharsets.UTF_8);
        transport.respondWith(new FeedTransport.FeedResponse(200, feed, "v1", null),
                new FeedTransport.FeedResponse(304, new byte[0], null, null));
        assertThat(collection.collect("world").status()).isEqualTo("STALE");
        assertThat(collection.collect("world").status()).isEqualTo("NOT_MODIFIED");
        assertThat(repository.source("world").health()).isEqualTo("STALE");
        assertThat(repository.source("world").etag()).isEqualTo("v1");
        assertThat(editions.preview(1, new PreviewRequest("old-source", CUTOFF)).sections().get(Category.WORLD).items()).isEmpty();
    }

    @Test
    void expiredLeaseCannotCommitAndFailedParsingReleasesLease() {
        repository.saveSource(source("world", Category.WORLD));
        assertThat(repository.acquireCollection("world", "old", CUTOFF.minusSeconds(121))).isTrue();
        assertThat(repository.acquireCollection("world", "current", CUTOFF)).isTrue();
        assertThatThrownBy(() -> repository.finishCollection("world", "old", CUTOFF, List.of(input("1")), null, null, "FRESH", CUTOFF))
                .isInstanceOf(NewsException.class);
        repository.finishCollection("world", "current", CUTOFF, List.of(), null, null, "EMPTY", null);
        transport.respondWith(new FeedTransport.FeedResponse(200, "<html/>".getBytes(StandardCharsets.UTF_8), null, null));
        assertThatThrownBy(() -> collection.collect("world")).isInstanceOf(NewsException.class);
        assertThat(repository.source("world").health()).isEqualTo("FETCH_FAILED");
        assertThat(repository.acquireCollection("world", "next", CUTOFF)).isTrue();
    }

    @Test
    void jschinaCollectionPersistsBodyAndPublisherTimeWithNormalCooldown() {
        String feedUrl = "https://jsnews.jschina.com.cn/jsyw/";
        String articleUrl = feedUrl + "202610/t20261018_s123.shtml";
        SourceSpec spec = new SourceSpec("jschina", "Jschina", feedUrl, Category.LOCAL, "xinhua-daily-group",
                "CN-JS", EvidencePolicy.EXCERPT_ALLOWED, feedUrl, 0.8, true, SourceKind.JSCHINA_JIANGSU);
        repository.saveSource(spec);
        String listing = "<div id='hideData'><ul class='dataList'><li><div class='datatit'><a href='" + articleUrl
                + "'>Local original event</a></div><div class='datatime'>2026-10-18 07:59</div></li></ul></div>";
        transport.respondWith(new FeedTransport.FeedResponse(200, listing.getBytes(StandardCharsets.UTF_8), "list-v1", null),
                new FeedTransport.FeedResponse(200, ("<div id='content' class='articalCont'><p>" + EVIDENCE + "</p></div>")
                        .getBytes(StandardCharsets.UTF_8), null, null));
        assertThat(collection.collect(spec.id()).inserted()).isEqualTo(1);
        Article article = repository.candidates(CUTOFF).getFirst();
        assertThat(article.url()).isEqualTo(articleUrl);
        assertThat(article.publishedAt()).isEqualTo(CUTOFF.minusSeconds(60));
        assertThat(article.evidenceText()).isEqualTo(EVIDENCE.strip());
        assertThat(repository.source(spec.id()).health()).isEqualTo("FRESH");
        assertThat(repository.source(spec.id()).nextAttemptAt()).isEqualTo(CUTOFF.plusSeconds(1800));
    }

    @Test
    void disabledSourcesCannotCollectOrEnterNewEditions() {
        SourceSpec source = source("world", Category.WORLD);
        repository.saveSource(source);
        repository.ingest("world", List.of(input("1")), CUTOFF.minusSeconds(1));
        repository.saveSource(new SourceSpec(source.id(), source.name(), source.feedUrl(), source.category(), source.publisherKey(),
                source.regionCode(), source.evidencePolicy(), source.rightsReference(), source.authority(), false));
        assertThatThrownBy(() -> collection.collect("world")).isInstanceOf(NewsException.class);
        assertThat(repository.candidates(CUTOFF)).isEmpty();
    }

    @Test
    void sourceRegistryCapacityReturnsRetryableSafeFailure() throws Exception {
        jdbc.update("UPDATE news_registry_capacity SET source_rows=1000 WHERE id=1");
        mvc.perform(put("/internal/admin/news/sources/extra").header("Authorization","Bearer test-source-admin-token")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Fixtures.source("extra",Category.WORLD))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NEWS_030"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_source WHERE id='extra'",Long.class)).isZero();
    }

    @Test
    void capacityFailureReportsSafeErrorKeepsEtagAndCoolsDownCollection() throws Exception {
        repository.saveSource(source("world",Category.WORLD));
        jdbc.update("UPDATE news_source SET etag='original' WHERE id='world'");
        // 用已满额度模拟其他来源占用空间，避免生成二十五万条测试文章。
        jdbc.update("UPDATE news_collection_capacity SET article_rows=250000 WHERE id=1");
        byte[] feed=("<rss><channel><item><title>Reported event</title><link>https://example.com/a</link>"
                +"<pubDate>Fri, 16 Oct 2026 00:00:00 GMT</pubDate><description>Evidence</description></item></channel></rss>").getBytes(StandardCharsets.UTF_8);
        transport.respondWith(new FeedTransport.FeedResponse(200,feed,"new-etag",null));
        mvc.perform(post("/internal/admin/news/sources/world/collect").header("Authorization","Bearer test-source-admin-token"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NEWS_028"));
        assertThat(repository.source("world").health()).isEqualTo("STORAGE_FULL");
        assertThat(repository.source("world").etag()).isEqualTo("original");
        assertThat(repository.source("world").nextAttemptAt()).isEqualTo(CUTOFF.plusSeconds(900));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_article",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT article_rows FROM news_collection_capacity",Long.class)).isEqualTo(250000);
    }

    private ArticleInput input(String id) {
        return new ArticleInput("https://example.com/" + id, "Reported original event " + id, CUTOFF.minusSeconds(60), EVIDENCE, 0);
    }

    @TestConfiguration
    static class FixedClock {
        /** 固定测试时钟，避免依赖运行机器日期。 */
        @Bean
        @Primary
        public Clock testClock() { return Clock.fixed(CUTOFF, ZoneOffset.UTC); }

        /** 提供离线传输替身，集成测试不能发出真实网络请求。 */
        @Bean
        @Primary
        public StubTransport testTransport() { return new StubTransport(); }
    }

    static class StubTransport implements FeedTransport {
        private final java.util.Deque<FeedResponse> responses = new java.util.ArrayDeque<>();

        void respondWith(FeedResponse... values) { responses.addAll(List.of(values)); }

        /** 消费预先声明的响应，意外采集调用直接使测试失败。 */
        @Override
        public FeedResponse fetch(SourceState source) { return responses.removeFirst(); }
    }
}
