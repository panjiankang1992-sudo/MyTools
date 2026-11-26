package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.mapper.GithubSnapshotRepository;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.service.DigestEngine;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import static com.yuyutian.mytools.news.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class TechnicalMigrationTest {
    @Test
    void populatedV1MigratesWithoutRewritingLegacyEvidenceOrEditions() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(dataSource).target("1").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        SourceSpec spec = source("legacy", Category.WORLD);
        Article old = article("a".repeat(64), spec, CUTOFF.minusSeconds(100), EVIDENCE);
        ObjectNode oldSource = json.valueToTree(spec);
        oldSource.remove("kind");
        ObjectNode oldArticle = json.valueToTree(old);
        oldArticle.remove("technical");
        oldArticle.remove("evidenceExpiredAt");
        oldArticle.set("source", oldSource);
        jdbc.update("INSERT INTO news_source(id, spec_json, enabled, health) VALUES (?, ?, ?, ?)",
                spec.id(), json.writeValueAsString(oldSource), true, "FRESH");
        String articlePayload = json.writeValueAsString(oldArticle);
        jdbc.update("INSERT INTO news_article(id, source_id, canonical_hash, content_hash, published_ms, fetched_ms, payload_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", old.id(), spec.id(), ContentIdentity.hash(old.url()), old.contentHash(),
                old.publishedAt().toEpochMilli(), old.fetchedAt().toEpochMilli(), articlePayload);
        UUID editionId = UUID.randomUUID();
        ObjectNode oldEdition = json.valueToTree(new DigestEngine().build(editionId, 17, CUTOFF, CUTOFF,
                ZoneId.of("Asia/Shanghai"), RegionSnapshot.unresolved(), List.of(old)));
        oldEdition.put("rankingVersion", "evidence-baseline-v1");
        ((ObjectNode) oldEdition.path("sections").path("WORLD").path("items").get(0)).remove("technical");
        String editionPayload = json.writeValueAsString(oldEdition);
        jdbc.update("INSERT INTO news_edition(id, owner_id, idempotency_hash, request_hash, cutoff_ms, created_ms, quality, payload_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", editionId.toString(), 17, ContentIdentity.hash("legacy-key"), "legacy-hash",
                CUTOFF.toEpochMilli(), CUTOFF.toEpochMilli(), "DEGRADED", editionPayload);

        // 先真实落下旧版记录，再运行增量迁移，不能用新建空库代替升级验证。
        assertThat(Flyway.configure().dataSource(dataSource).target("2").load().migrate().migrationsExecuted).isEqualTo(1);
        var snapshots = new GithubSnapshotRepository(jdbc, json, Fixtures.capacity(jdbc), Fixtures.transaction(jdbc));
        var repository = new NewsRepository(jdbc, json, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), snapshots, Fixtures.capacity(jdbc), Fixtures.control(jdbc), Fixtures.registry(jdbc));
        assertThat(repository.source(spec.id()).spec().kind()).isEqualTo(SourceKind.RSS_ATOM);
        assertThat(repository.source(spec.id()).nextAttemptAt()).isNull();
        assertThat(repository.candidates(CUTOFF)).containsExactly(old);
        assertThat(jdbc.queryForObject("SELECT ranking_ms FROM news_article WHERE id = ?", Long.class, old.id()))
                .isEqualTo(old.publishedAt().toEpochMilli());
        assertThat(jdbc.queryForObject("SELECT payload_json FROM news_article WHERE id = ?", String.class, old.id())).isEqualTo(articlePayload);
        assertThat(jdbc.queryForObject("SELECT payload_json FROM news_edition WHERE id = ?", String.class, editionId.toString())).isEqualTo(editionPayload);
        Edition restored = repository.edition(17, editionId);
        assertThat(restored.rankingVersion()).isEqualTo("evidence-baseline-v1");
        assertThat(restored.sections().get(Category.WORLD).items().getFirst().technical()).isNull();
        assertThat(repository.findByKey(17, "legacy-key", "legacy-hash")).contains(restored);
        assertThat(Flyway.configure().dataSource(dataSource).target("2").load().migrate().migrationsExecuted).isZero();
        assertThat(Flyway.configure().dataSource(dataSource).target("3").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(Flyway.configure().dataSource(dataSource).target("4").load().migrate().migrationsExecuted).isEqualTo(1);
        String legacyRun = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO news_task_outbox(run_id, state, available_ms) VALUES (?, 'PENDING', 0)", legacyRun);
        assertThat(Flyway.configure().dataSource(dataSource).target("5").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT job_kind FROM news_task_outbox WHERE run_id = ?", String.class, legacyRun)).isEqualTo("EDITION");
        assertThat(Flyway.configure().dataSource(dataSource).target("6").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_dispatch_outbox",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_delivery_batch",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM news_task_outbox WHERE run_id=?",String.class,legacyRun)).isEqualTo("PENDING");
        assertThat(Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_job",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT payload_json FROM news_edition WHERE id=?",String.class,editionId.toString())).isEqualTo(editionPayload);
        assertThat(jdbc.queryForObject("SELECT last_status FROM news_retention_state WHERE id=1",String.class)).isEqualTo("NEVER_RUN");
        assertThat(repository.edition(17, editionId)).isEqualTo(restored);
    }
}
