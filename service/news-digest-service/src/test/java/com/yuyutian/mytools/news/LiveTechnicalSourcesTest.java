package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.config.TechSourceProperties;
import com.yuyutian.mytools.news.mapper.GithubSnapshotRepository;
import com.yuyutian.mytools.news.service.HttpFeedTransport;
import com.yuyutian.mytools.news.service.technical.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅显式启用的公共 API 探测，无凭据、无数据库写入、无消息发送。 */
@EnabledIfSystemProperty(named = "news.liveTechProbe", matches = "true")
class LiveTechnicalSourcesTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final JsonSourceClient client = new JsonSourceClient(new HttpFeedTransport(), json);
    private final TechSourceProperties limits = new TechSourceProperties("", 3, 0, 0);
    private final Clock clock = Clock.systemUTC();

    @Test
    void githubPublicSearchParsesActualAggregateMetadata() {
        // 跟踪预算为零，仓储不会建立连接；真实写入由离线事务测试单独验证。
        var collector = new GithubCollector(client, limits, new GithubSnapshotRepository(new JdbcTemplate(), json, Fixtures.capacity(new JdbcTemplate()), Fixtures.transaction(new JdbcTemplate())), clock);
        CollectedBatch batch = collector.collect(TechnicalCollectorsTest.state(SourceKind.GITHUB, EvidencePolicy.METADATA_ONLY), clock.instant());
        assertThat(batch.snapshots()).isNotEmpty();
        assertThat(batch.coverage().failed()).isZero();
        report("GITHUB", batch);
    }

    @Test
    void hackerNewsOfficialListAndItemsUseRealPublicationTimes() {
        CollectedBatch batch = new HackerNewsCollector(client, limits)
                .collect(TechnicalCollectorsTest.state(SourceKind.HACKER_NEWS, EvidencePolicy.METADATA_ONLY), clock.instant());
        assertThat(batch.coverage().visited()).isGreaterThan(0);
        assertThat(batch.coverage().failed()).isZero();
        assertThat(batch.articles()).isNotEmpty();
        report("HACKER_NEWS", batch);
    }

    @Test
    void devOfficialWeeklyListParsesPublishedArticles() {
        CollectedBatch batch = new DevCollector(client, limits)
                .collect(TechnicalCollectorsTest.state(SourceKind.DEV, EvidencePolicy.METADATA_ONLY), clock.instant());
        assertThat(batch.articles()).isNotEmpty();
        assertThat(batch.coverage().failed()).isZero();
        report("DEV", batch);
    }

    private void report(String provider, CollectedBatch batch) {
        System.out.println("TECH_PROBE provider=" + provider + " visited=" + batch.coverage().visited()
                + " accepted=" + batch.coverage().accepted() + " skipped=" + batch.coverage().skipped()
                + " failed=" + batch.coverage().failed());
    }
}
