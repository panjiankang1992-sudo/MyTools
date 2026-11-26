package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import java.time.Instant;

/** 测试固定材料只在本地使用，不是实际新闻。 */
final class Fixtures {
    static final Instant CUTOFF = Instant.parse("2026-10-18T00:00:00Z");
    static final String EVIDENCE = "A verified source paragraph describes the reported event, its participants, the timeline, "
            + "and the available evidence. The report contains enough original detail to support a traceable excerpt "
            + "without creating additional claims or assuming that a headline proves the full story. ";

    private Fixtures() { }

    static com.yuyutian.mytools.news.mapper.RegistryCapacityRepository registry(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new com.yuyutian.mytools.news.mapper.RegistryCapacityRepository(jdbc,
                new com.yuyutian.mytools.news.config.RegistryCapacityProperties(1000,10000,1000,100000,10000));
    }

    static void resetRegistry(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        // 测试清空业务夹具后同步实际计数，生产代码不提供重置账本入口。
        jdbc.update("UPDATE news_registry_capacity SET source_rows=(SELECT COUNT(*) FROM news_source),pack_rows=(SELECT COUNT(*) FROM news_region_source_pack),node_rows=(SELECT COUNT(*) FROM news_region_schedule),region_jobs=(SELECT COUNT(*) FROM news_region_job),direct_observations=(SELECT COUNT(*) FROM news_region_observation WHERE capacity_kind='DIRECT') WHERE id=1");
    }

    static com.yuyutian.mytools.news.mapper.ControlCapacityRepository control(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new com.yuyutian.mytools.news.mapper.ControlCapacityRepository(jdbc,
                new com.yuyutian.mytools.news.config.ControlCapacityProperties(1000,1000,100000,100000));
    }

    static void resetControl(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM news_content_identity");
        jdbc.update("UPDATE news_control_capacity SET subscription_rows=0,target_owners=0,daily_runs=0,preview_identities=0 WHERE id=1");
    }


    static com.yuyutian.mytools.news.mapper.CollectionCapacityRepository capacity(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new com.yuyutian.mytools.news.mapper.CollectionCapacityRepository(jdbc,
                new com.yuyutian.mytools.news.config.CollectionCapacityProperties(250000,250000,100000,536870912));
    }

    static org.springframework.transaction.support.TransactionTemplate transaction(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        if (jdbc.getDataSource()==null) return new org.springframework.transaction.support.TransactionTemplate();
        return new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }


    static SourceSpec source(String id, Category category) {
        return new SourceSpec(id, id, "https://example.com/" + id + ".xml", category, id,
                category == Category.LOCAL ? "CN-TEST-CITY" : null, EvidencePolicy.EXCERPT_ALLOWED,
                "test-fixture-only", 0.8, true);
    }

    static Article article(String id, SourceSpec source, Instant published, String evidence) {
        return new Article(id, source, "https://example.com/article/" + id, "Unique reported event " + id,
                published, CUTOFF.minusSeconds(1), evidence, ContentIdentity.hash(evidence), 0);
    }
}
