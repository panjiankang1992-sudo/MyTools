package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.config.TechSourceProperties;
import com.yuyutian.mytools.news.mapper.GithubSnapshotRepository;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.service.CollectionService;
import com.yuyutian.mytools.news.service.DigestEngine;
import com.yuyutian.mytools.news.service.EditionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:news-tech;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "news.internal-token=test-proxy", "news.admin-token=test-admin", "news.collection-enabled=true",
        "news.tech.item-limit=3", "news.tech.tracked-limit=2", "news.tech.github-token="
})
@AutoConfigureMockMvc
class TechnicalPersistenceTest {
    @Autowired private NewsRepository repository;
    @Autowired private GithubSnapshotRepository snapshots;
    @Autowired private CollectionService collection;
    @Autowired private EditionService editions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private TechnicalCollectorsTest.ScriptedTransport transport;
    @Autowired private ObjectMapper json;
    @Autowired private MockMvc mvc;
    @Autowired private TechSourceProperties properties;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM news_edition");
        jdbc.update("DELETE FROM news_region_snapshot");
        Fixtures.resetControl(jdbc);
        Fixtures.resetRegistry(jdbc);
        jdbc.update("DELETE FROM news_article");
        jdbc.update("DELETE FROM news_source_withdrawal");
        jdbc.update("DELETE FROM news_provider_cooldown");
        jdbc.update("DELETE FROM news_github_snapshot");
        jdbc.update("DELETE FROM news_source");
        Fixtures.resetRegistry(jdbc);
        jdbc.update("UPDATE news_collection_capacity SET article_rows=0,snapshot_rows=0,withdrawal_rows=0,payload_bytes=0 WHERE id=1");
        transport.responses.clear();
        transport.requests.clear();
        transport.rateLimitedUrl = null;
        clock.now.set(Fixtures.CUTOFF);
    }

    @Test
    void githubCollectorPersistsOriginalCreationTimeAndColdStartWithoutUsingTotalStarsAsHeat() {
        SourceSpec spec = source();
        repository.saveSource(spec);
        transport.respond(searchUrl(), search(repositoryJson(1, "owner/project", 1000000), false, 500));
        CollectionResult result = collection.collect(spec.id());
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.newestPublishedAt()).isEqualTo(Instant.parse("2015-01-01T00:00:00Z"));
        assertThat(result.newestObservedAt()).isEqualTo(Fixtures.CUTOFF);
        assertThat(result.coverage().limitations()).contains("SEARCH_FIRST_PAGE_ONLY", "SEARCH_DISCOVERY_NOT_WEEKLY_TRENDING");
        clock.now.set(Fixtures.CUTOFF.plusSeconds(1));
        Edition edition = editions.preview(1, new PreviewRequest("cold", clock.instant()));
        RankedItem item = edition.sections().get(Category.TECH).items().getFirst();
        assertThat(item.publishedAt()).isEqualTo(Instant.parse("2015-01-01T00:00:00Z"));
        assertThat(item.technical().activityAt()).isEqualTo(Fixtures.CUTOFF);
        assertThat(item.technical().github().netStars()).isNull();
        assertThat(item.score().engagement()).isZero();
        assertThat(item.score().freshness()).isZero();
        assertThat(edition.sections().get(Category.TECH).gaps()).contains("GITHUB_TREND_WARMING_UP");
        assertThat(snapshots.history(spec.id(), 1, clock.instant()).getFirst().description()).isEmpty();
        assertThat(repository.source(spec.id()).nextAttemptAt()).isEqualTo(Fixtures.CUTOFF.plus(Duration.ofHours(6)));
        int requests = transport.requests.size();
        assertThatThrownBy(() -> collection.collect(spec.id())).isInstanceOf(SourceRateLimitException.class);
        assertThat(transport.requests).hasSize(requests);
    }

    @Test
    void continuousSnapshotsEnterRankingByNetGrowthAndSurviveRepositoryRecreation() {
        SourceSpec spec = source();
        repository.saveSource(spec);
        for (int index = 0; index <= 28; index++) {
            Instant observed = Fixtures.CUTOFF.minus(Duration.ofHours((28L - index) * 6));
            commitSnapshot(spec, snapshot(1, "owner/project", observed, 1000 + index * 10), "round-" + index);
        }
        clock.now.set(Fixtures.CUTOFF.plusSeconds(1));
        Edition edition = editions.preview(1, new PreviewRequest("warm", clock.instant()));
        RankedItem item = edition.sections().get(Category.TECH).items().getFirst();
        assertThat(item.technical().github().status()).isEqualTo(TrendStatus.READY);
        assertThat(item.technical().github().netStars()).isEqualTo(280);
        assertThat(item.score().engagement()).isEqualTo(1);
        assertThat(new GithubSnapshotRepository(new JdbcTemplate(jdbc.getDataSource()), json, Fixtures.capacity(new JdbcTemplate(jdbc.getDataSource())), Fixtures.transaction(new JdbcTemplate(jdbc.getDataSource())))
                .history(spec.id(), 1, clock.instant())).hasSize(29);
        clock.now.set(Fixtures.CUTOFF.plus(Duration.ofHours(13)));
        RankedItem stale = editions.preview(1, new PreviewRequest("stale", clock.instant())).sections().get(Category.TECH).items().getFirst();
        assertThat(stale.technical().github().status()).isEqualTo(TrendStatus.STALE);
        assertThat(stale.score().engagement()).isZero();
        assertThat(editions.edition(1, edition.id())).isEqualTo(edition);
    }

    @Test
    void sameBucketCannotOverwriteMetricsAndExpiredLeaseCannotInsertSnapshots() {
        SourceSpec spec = source();
        repository.saveSource(spec);
        commitSnapshot(spec, snapshot(1, "owner/project", Fixtures.CUTOFF, 100), "first");
        // 清除测试中的成功冷却以单独验证桶唯一约束，而不是模拟生产操作。
        jdbc.update("UPDATE news_source SET next_attempt_ms = NULL WHERE id = ?", spec.id());
        commitSnapshot(spec, snapshot(1, "owner/project", Fixtures.CUTOFF.plusSeconds(10), 999), "retry");
        assertThat(snapshots.history(spec.id(), 1, Fixtures.CUTOFF.plusSeconds(20))).hasSize(1);
        assertThat(snapshots.history(spec.id(), 1, Fixtures.CUTOFF.plusSeconds(20)).getFirst().stars()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_article", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> repository.finishTechnicalCollection(spec.id(), "old-lease", Fixtures.CUTOFF,
                batch(snapshot(2, "owner/second", Fixtures.CUTOFF, 1)), "FRESH", Fixtures.CUTOFF)).isInstanceOf(NewsException.class);
        assertThat(snapshots.history(spec.id(), 2, Fixtures.CUTOFF)).isEmpty();
    }

    @Test
    void rateLimitIsDurableAndReenablingDoesNotClearCooldown() throws Exception {
        SourceSpec spec = source();
        repository.saveSource(spec);
        transport.rateLimitedUrl = searchUrl();
        mvc.perform(post("/internal/admin/news/sources/" + spec.id() + "/collect").header("Authorization", "Bearer test-admin"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("NEWS_012"))
                .andExpect(header().exists("Retry-After"));
        assertThat(repository.source(spec.id()).health()).isEqualTo("RATE_LIMITED");
        repository.saveSource(new SourceSpec(spec.id(), spec.name(), spec.feedUrl(), spec.category(), spec.publisherKey(), spec.regionCode(),
                spec.evidencePolicy(), spec.rightsReference(), spec.authority(), false, spec.kind()));
        repository.saveSource(spec);
        int requests = transport.requests.size();
        assertThatThrownBy(() -> collection.collect(spec.id())).isInstanceOf(SourceRateLimitException.class);
        assertThat(transport.requests).hasSize(requests);
        assertThat(repository.source(spec.id()).nextAttemptAt()).isEqualTo(Fixtures.CUTOFF.plusSeconds(600));
        SourceSpec other = new SourceSpec("github-other", spec.name(), spec.feedUrl(), spec.category(), spec.publisherKey(), spec.regionCode(),
                spec.evidencePolicy(), spec.rightsReference(), spec.authority(), true, spec.kind());
        repository.saveSource(other);
        assertThatThrownBy(() -> collection.collect(other.id())).isInstanceOf(SourceRateLimitException.class);
        assertThat(transport.requests).hasSize(requests);
    }

    @Test
    void collectedHnExternalExcerptKeepsItsActualCitationAfterPersistence() {
        SourceSpec spec = TechnicalCollectorsTest.state(SourceKind.HACKER_NEWS, EvidencePolicy.EXCERPT_ALLOWED).spec();
        repository.saveSource(spec);
        String page = "https://example.org/original-article";
        transport.respond(spec.feedUrl(), "[1]");
        transport.respond("https://hacker-news.firebaseio.com/v0/item/1.json",
                "{\"id\":1,\"type\":\"story\",\"time\":" + Fixtures.CUTOFF.minusSeconds(120).getEpochSecond()
                        + ",\"title\":\"Original public article\",\"url\":\"" + page + "\",\"score\":10,\"descendants\":2}");
        transport.respond(page, "<article><p>" + Fixtures.EVIDENCE + "</p></article>");
        collection.collect(spec.id());
        clock.now.set(Fixtures.CUTOFF.plusSeconds(1));
        RankedItem item = editions.preview(1, new PreviewRequest("external-excerpt", clock.instant()))
                .sections().get(Category.TECH).items().getFirst();
        assertThat(item.detailStatus()).isEqualTo(DetailStatus.AVAILABLE);
        assertThat(item.detail().citations().getFirst().url()).isEqualTo(page);
        assertThat(repository.candidates(clock.instant()).getFirst().technical().evidenceUrl()).isEqualTo(page);
        assertThat(item.technical().discussionUrl()).isEqualTo("https://news.ycombinator.com/item?id=1");
    }

    @Test
    void withdrawnStoryDisappearsFromNewEditionsAndCanReturnWithoutChangingOldEdition() {
        SourceSpec spec = TechnicalCollectorsTest.state(SourceKind.HACKER_NEWS, EvidencePolicy.METADATA_ONLY).spec();
        repository.saveSource(spec);
        String story = "{\"id\":1,\"type\":\"story\",\"time\":" + Fixtures.CUTOFF.minusSeconds(120).getEpochSecond()
                + ",\"title\":\"Original surviving story\",\"score\":10,\"descendants\":1}";
        transport.respond(spec.feedUrl(), "[1]");
        transport.respond("https://hacker-news.firebaseio.com/v0/item/1.json", story);
        collection.collect(spec.id());
        clock.now.set(Fixtures.CUTOFF.plusSeconds(1));
        Edition original = editions.preview(1, new PreviewRequest("original", clock.instant()));
        assertThat(original.sections().get(Category.TECH).headlineCount()).isEqualTo(1);
        clock.now.set(Fixtures.CUTOFF.plus(Duration.ofMinutes(15)));
        transport.respond("https://hacker-news.firebaseio.com/v0/item/1.json", "{\"id\":1,\"deleted\":true}");
        collection.collect(spec.id());
        assertThat(repository.candidates(clock.instant().plusSeconds(1))).isEmpty();
        assertThat(repository.candidates(Fixtures.CUTOFF.plusSeconds(1))).hasSize(1);
        assertThat(editions.edition(1, original.id())).isEqualTo(original);
        clock.now.set(Fixtures.CUTOFF.plus(Duration.ofMinutes(30)));
        transport.respond("https://hacker-news.firebaseio.com/v0/item/1.json", story);
        collection.collect(spec.id());
        assertThat(repository.candidates(clock.instant().plusSeconds(1))).hasSize(1);
    }

    @Test
    void snapshotAndArticleRollBackTogetherWhenBatchCannotBeCommitted() {
        SourceSpec spec = source();
        repository.saveSource(spec);
        assertThat(repository.acquireCollection(spec.id(), "broken", Fixtures.CUTOFF)).isTrue();
        TechnicalMetadata invalidMetadata = new TechnicalMetadata("2", "https://github.com/owner/bad", Fixtures.CUTOFF, 1, 0, null);
        CollectedArticle invalid = new CollectedArticle(new ArticleInput("file:///invalid", "Invalid item", Fixtures.CUTOFF, "", 0), invalidMetadata);
        CollectedBatch batch = new CollectedBatch(List.of(invalid), List.of(snapshot(1, "owner/project", Fixtures.CUTOFF, 100)),
                new CollectionCoverage(2, 2, 2, 0, 0, List.of()));
        assertThatThrownBy(() -> repository.finishTechnicalCollection(spec.id(), "broken", Fixtures.CUTOFF, batch, "FRESH", Fixtures.CUTOFF))
                .isInstanceOf(NewsException.class);
        assertThat(snapshots.history(spec.id(), 1, Fixtures.CUTOFF)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_article", Integer.class)).isZero();
    }

    @Test
    void trackedRepositoriesRemainObservedWhenTheyDisappearFromSearch() {
        SourceSpec spec = source();
        repository.saveSource(spec);
        commitSnapshot(spec, snapshot(1, "owner/old-project", Fixtures.CUTOFF.minus(Duration.ofHours(6)), 100), "seed");
        transport.respond(searchUrl(), search(repositoryJson(2, "owner/new-project", 200), true, 20));
        transport.respond("https://api.github.com/repos/owner/old-project", repositoryJson(1, "owner/renamed-project", 105));
        CollectionResult result = collection.collect(spec.id());
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.coverage().limitations()).contains("UPSTREAM_INCOMPLETE_SEARCH");
        assertThat(snapshots.history(spec.id(), 1, Fixtures.CUTOFF)).hasSize(2);
        assertThat(repository.candidates(Fixtures.CUTOFF.plusSeconds(1))).hasSize(2);
        assertThat(repository.candidates(Fixtures.CUTOFF.plusSeconds(1))).anyMatch(article -> article.title().equals("owner/renamed-project"));
    }

    @Test
    void privateForkArchivedAndMissingCountersAreNotSnapshotted() {
        SourceSpec spec = source();
        repository.saveSource(spec);
        String valid = repositoryJson(1, "owner/private", 2).replace("\"private\":false", "\"private\":true");
        String fork = repositoryJson(2, "owner/fork", 3).replace("\"fork\":false", "\"fork\":true");
        String invalid = repositoryJson(3, "owner/invalid", 4).replace("\"stargazers_count\":4", "\"stargazers_count\":null");
        transport.respond(searchUrl(), search(valid + "," + fork + "," + invalid, false, 3));
        CollectionResult result = collection.collect(spec.id());
        assertThat(result.coverage().skipped()).isEqualTo(2);
        assertThat(result.coverage().failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_github_snapshot", Integer.class)).isZero();
    }

    @Test
    void rssV1PayloadsRemainReadableAndApiSourcesRejectManualMetricImports() throws Exception {
        SourceSpec rss = json.readValue("{\"id\":\"old\",\"name\":\"old\",\"feedUrl\":\"https://example.com/rss\",\"category\":\"WORLD\","
                + "\"publisherKey\":\"old\",\"evidencePolicy\":\"METADATA_ONLY\",\"rightsReference\":\"test\",\"authority\":0.5,\"enabled\":true}", SourceSpec.class);
        assertThat(rss.kind()).isEqualTo(SourceKind.RSS_ATOM);
        repository.saveSource(source());
        assertThatThrownBy(() -> repository.ingest(source().id(), List.of(new ArticleInput("https://github.com/test/repo", "fake metrics", Fixtures.CUTOFF, "", 999)), Fixtures.CUTOFF))
                .isInstanceOf(NewsException.class);
    }

    @Test
    void futureSampleDoesNotEnterPastEdition() {
        SourceSpec spec = source();
        repository.saveSource(spec);
        commitSnapshot(spec, snapshot(1, "owner/project", Fixtures.CUTOFF, 100), "now");
        commitSnapshot(spec, snapshot(1, "owner/project", Fixtures.CUTOFF.plus(Duration.ofHours(6)), 999), "future");
        List<Article> articles = repository.candidates(Fixtures.CUTOFF.plusSeconds(1));
        var edition = new DigestEngine().build(UUID.randomUUID(), 1, Fixtures.CUTOFF.plusSeconds(1), Fixtures.CUTOFF.plusSeconds(2),
                ZoneId.of("UTC"), RegionSnapshot.unresolved(), articles);
        assertThat(edition.sections().get(Category.TECH).items().getFirst().technical().github().stars()).isEqualTo(100);
    }

    private void commitSnapshot(SourceSpec source, GithubSnapshot snapshot, String token) {
        assertThat(repository.acquireCollection(source.id(), token, snapshot.observedAt())).isTrue();
        repository.finishTechnicalCollection(source.id(), token, snapshot.observedAt(), batch(snapshot), "FRESH", snapshot.observedAt());
    }

    private CollectedBatch batch(GithubSnapshot snapshot) {
        return new CollectedBatch(List.of(), List.of(snapshot), new CollectionCoverage(1, 1, 1, 0, 0, List.of()));
    }

    private SourceSpec source() { return TechnicalCollectorsTest.state(SourceKind.GITHUB, EvidencePolicy.METADATA_ONLY).spec(); }

    private GithubSnapshot snapshot(long id, String name, Instant observed, long stars) {
        return new GithubSnapshot(id, name, "https://github.com/" + name, Instant.parse("2015-01-01T00:00:00Z"), observed,
                "Source description", stars, 10, observed);
    }

    private String searchUrl() {
        return "https://api.github.com/search/repositories?q=pushed%3A%3E%3D2026-10-11%20archived%3Afalse%20fork%3Afalse&sort=stars&order=desc&per_page="
                + properties.itemLimit() + "&page=1";
    }

    private String search(String items, boolean incomplete, long count) {
        return "{\"total_count\":" + count + ",\"incomplete_results\":" + incomplete + ",\"items\":[" + items + "]}";
    }

    private String repositoryJson(long id, String name, long stars) {
        return "{\"id\":" + id + ",\"full_name\":\"" + name + "\",\"html_url\":\"https://github.com/" + name
                + "\",\"private\":false,\"archived\":false,\"fork\":false,\"created_at\":\"2015-01-01T00:00:00Z\","
                + "\"pushed_at\":\"2026-10-17T00:00:00Z\",\"stargazers_count\":" + stars + ",\"forks_count\":10,\"description\":\"Description\"}";
    }

    /** 测试专用的可控时钟和离线传输配置。 */
    @TestConfiguration
    static class TestBeans {
        /** 提供可控时间，用于验证真实时间窗和持久冷却。 */
        @Bean @Primary
        public MutableClock mutableClock() { return new MutableClock(); }

        /** 技术集成测试使用 URL 校验的离线响应。 */
        @Bean @Primary
        public TechnicalCollectorsTest.ScriptedTransport scriptedTransport() { return new TechnicalCollectorsTest.ScriptedTransport(); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Fixtures.CUTOFF);
        /** 返回固定协调世界时时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 创建共享当前时刻的其他时区视图。 */
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        /** 返回测试控制的当前时刻。 */
        @Override public Instant instant() { return now.get(); }
    }
}
