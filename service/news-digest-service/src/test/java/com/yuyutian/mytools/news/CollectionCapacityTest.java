package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.*;
import com.yuyutian.mytools.news.mapper.*;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class CollectionCapacityTest {
    private static final Instant NOW=Fixtures.CUTOFF;
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private NewsRepository news;
    private CollectionCapacityProperties limits=new CollectionCapacityProperties(1,1,1,1_000_000);
    @BeforeEach void setup() {
        JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:mem:capacity-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        Flyway.configure().dataSource(data).load().migrate();jdbc=new JdbcTemplate(data);news=repository(jdbc,limits);
        news.saveSource(Fixtures.source("one",Category.TECH));
    }
    @Test void exactRowLimitStillAllowsReplayAndRollsBackWholeNewBatch() {
        news.ingest("one",List.of(input("a",NOW)),NOW);
        Map<String,Object> before=usage(jdbc);
        assertThat(news.ingest("one",List.of(input("a",NOW)),NOW)).isZero();
        rejected(()->news.ingest("one",List.of(input("a",NOW),input("b",NOW)),NOW));
        assertThat(usage(jdbc)).isEqualTo(before);
        assertThat(news.candidates(NOW.plusSeconds(1))).hasSize(1);
    }
    @Test void utf8PayloadBudgetCountsBytesAndPreallocatedExpirySpace() {
        news.ingest("one",List.of(new ArticleInput("https://example.com/unicode","\u65b0\u95fb \ud83c\udf0f",NOW,"\u8be6\u60c5\ud83d\ude80".repeat(100),0)),NOW);
        String body=jdbc.queryForObject("SELECT payload_json FROM news_article",String.class);
        long used=jdbc.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class);
        assertThat(used).isEqualTo(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+128L).isGreaterThan(body.length()+128L);
        news=repository(jdbc,new CollectionCapacityProperties(10,10,10,used));
        rejected(()->news.ingest("one",List.of(input("b",NOW)),NOW));
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class)).isEqualTo(used);
    }
    @Test void failingTechnicalBatchRollsBackSnapshotsWithdrawalsArticlesAndCheckpoint() {
        source("github",SourceKind.GITHUB,"https://api.github.com/search/repositories");
        assertThat(news.acquireCollection("github","lease",NOW)).isTrue();
        var batch=new CollectedBatch(List.of(),List.of(snapshot(1,NOW),snapshot(2,NOW)),null,List.of("removed"));
        rejected(()->news.finishTechnicalCollection("github","lease",NOW,batch,"FRESH",NOW));
        assertEmpty(jdbc);
        assertThat(news.source("github").newestObservedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM news_source WHERE id='github'",String.class)).isEqualTo("lease");
    }
    @Test void standaloneSnapshotSaveIsTransactionalAndReplaysOriginalBucket() {
        var capacity=new CollectionCapacityRepository(jdbc,limits);
        var snapshots=new GithubSnapshotRepository(jdbc,JSON,capacity,Fixtures.transaction(jdbc));
        GithubSnapshot original=snapshots.save("one",snapshot(1,NOW));
        assertThat(snapshots.save("one",snapshot(1,NOW.plusSeconds(1)))).isEqualTo(original);
        rejected(()->snapshots.save("one",snapshot(2,NOW)));
        assertThat(jdbc.queryForObject("SELECT snapshot_rows FROM news_collection_capacity",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_github_snapshot",Long.class)).isEqualTo(1);
    }
    @Test void withdrawalCapKeepsExistingTombstoneAndDoesNotChargeReplay() {
        source("hn",SourceKind.HACKER_NEWS,"https://hacker-news.firebaseio.com/v0/topstories.json");
        var first=new CollectedBatch(List.of(),List.of(),null,List.of("gone"));
        news.acquireCollection("hn","lease",NOW);
        news.finishTechnicalCollection("hn","lease",NOW,first,"EMPTY",null);
        jdbc.update("UPDATE news_source SET next_attempt_ms=NULL WHERE id='hn'");
        news.acquireCollection("hn","lease2",NOW);
        news.finishTechnicalCollection("hn","lease2",NOW,first,"EMPTY",null);
        jdbc.update("UPDATE news_source SET next_attempt_ms=NULL WHERE id='hn'");
        news.acquireCollection("hn","lease3",NOW);
        rejected(()->news.finishTechnicalCollection("hn","lease3",NOW,new CollectedBatch(List.of(),List.of(),null,List.of("other")),"EMPTY",null));
        assertThat(jdbc.queryForObject("SELECT withdrawal_rows FROM news_collection_capacity",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT external_id FROM news_source_withdrawal",String.class)).isEqualTo("gone");
    }
    @Test void concurrentRepositoriesCannotSpendTheSameRemainingSlot() throws Exception { concurrentAdmission(jdbc); }
    @Test void outerTransactionRollbackAlsoRollsBackCapacityReservation() {
        assertThatThrownBy(()->Fixtures.transaction(jdbc).executeWithoutResult(status->{
            news.ingest("one",List.of(input("a",NOW)),NOW);
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertEmpty(jdbc);
        assertThat(news.ingest("one",List.of(input("b",NOW)),NOW)).isEqualTo(1);
    }
    @Test void compactionReleasesBytesAndDeletionReleasesSlotsWithoutRestoringEvidence() {
        Instant old=NOW.minus(Duration.ofDays(8));news.ingest("one",List.of(input("old",old)),old);
        long before=jdbc.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class);
        var retention=retention(limits);retention.sweep(NOW);
        long after=jdbc.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class);
        assertThat(after).isLessThan(before);
        assertThat(after).isEqualTo(jdbc.queryForObject("SELECT OCTET_LENGTH(payload_json) FROM news_article",Long.class));
        assertThat(news.ingest("one",List.of(input("old",old)),old)).isZero();
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class)).isEqualTo(after);
        retention.sweep(NOW.plus(Duration.ofDays(90)));assertEmpty(jdbc);
        assertThat(news.ingest("one",List.of(input("new",NOW)),NOW)).isEqualTo(1);
    }
    @Test void loweringLimitsPreservesExistingRowsAndStillPermitsCleanup() {
        news=repository(jdbc,new CollectionCapacityProperties(10,10,10,1_000_000));
        Instant old=NOW.minus(Duration.ofDays(91));news.ingest("one",List.of(input("a",old),input("b",old)),old);
        news=repository(jdbc,new CollectionCapacityProperties(1,1,1,1));
        assertThat(news.ingest("one",List.of(input("a",old)),old)).isZero();
        rejected(()->news.ingest("one",List.of(input("c",NOW)),NOW));
        retention(new CollectionCapacityProperties(1,1,1,1)).sweep(NOW);assertEmpty(jdbc);
    }
    @Test void inconsistentLedgerFailsClosedAndRollsBackContentChanges() {
        Instant old=NOW.minus(Duration.ofDays(91));news.ingest("one",List.of(input("a",old)),old);
        String before=jdbc.queryForObject("SELECT payload_json FROM news_article",String.class);
        jdbc.update("UPDATE news_collection_capacity SET payload_bytes=0 WHERE id=1");
        assertThatThrownBy(()->retention(limits).sweep(NOW)).isInstanceOf(NewsException.class);
        assertThat(jdbc.queryForObject("SELECT payload_json FROM news_article",String.class)).isEqualTo(before);
    }
    @Test void capacityMutationRequiresTransactionAndMissingLedgerDoesNotResetUsage() {
        var capacity=new CollectionCapacityRepository(jdbc,limits);
        assertThatThrownBy(capacity::lock).isInstanceOf(NewsException.class);
        assertThatThrownBy(()->capacity.adjust(1,0,0,1)).isInstanceOf(NewsException.class);
        jdbc.update("DELETE FROM news_collection_capacity");
        assertThatThrownBy(()->news.ingest("one",List.of(input("a",NOW)),NOW)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_article",Long.class)).isZero();
    }
    @Test void articleExpiryWithoutEvidenceUsesReservedSpaceAndFreesRemainder() {
        Instant old=NOW.minus(Duration.ofDays(8));news.ingest("one",List.of(new ArticleInput("https://example.com/a","Title",old,"",0)),old);
        long before=jdbc.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class);
        retention(limits).sweep(NOW);
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class)).isLessThan(before);
        assertThat(jdbc.queryForObject("SELECT evidence_expired_ms FROM news_article",Long.class)).isEqualTo(NOW.toEpochMilli());
    }
    @Test void populatedV7BackfillsUsageAndRejectsOldUnmeteredInserts() throws Exception {
        JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:mem:capacity-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(data).target("7").load().migrate();
        JdbcTemplate database=new JdbcTemplate(data);seedLegacy(database);
        String before=database.queryForObject("SELECT payload_json FROM news_article",String.class);
        assertThat(Flyway.configure().dataSource(data).target("8").load().migrate().migrationsExecuted).isEqualTo(1);
        assertMigratedUsage(database,before);
    }
    @Test void concurrentCleanupAndIngestKeepLedgerConsistent() throws Exception { concurrentCleanup(jdbc); }
    @Test void administrativeAuditReportsDriftWithoutResettingLedgerOrReturningContent() throws Exception {
        news.ingest("one",List.of(input("a",NOW)),NOW);
        var capacity=new CollectionCapacityRepository(jdbc,limits);
        assertThat(capacity.status().get("accountingMatches")).isEqualTo(true);
        jdbc.update("UPDATE news_collection_capacity SET payload_bytes=payload_bytes+1 WHERE id=1");
        assertThat(capacity.status().get("accountingMatches")).isEqualTo(false);
        assertThat(JSON.writeValueAsString(capacity.status())).doesNotContain("example.com",Fixtures.EVIDENCE,"owner");
        assertThat(jdbc.queryForObject("SELECT payload_bytes-(SELECT SUM(capacity_bytes) FROM news_article) FROM news_collection_capacity",Long.class)).isEqualTo(1);
    }

    static void seedLegacy(JdbcTemplate database) throws Exception {
        SourceSpec source=Fixtures.source("legacy",Category.TECH);
        Article article=Fixtures.article("a".repeat(64),source,NOW.minusSeconds(100),"\u65b0\u95fb\ud83c\udf0f");
        database.update("INSERT INTO news_source(id,spec_json,enabled,health) VALUES(?,?,TRUE,'FRESH')",source.id(),JSON.writeValueAsString(source));
        database.update("INSERT INTO news_article(id,source_id,canonical_hash,content_hash,published_ms,fetched_ms,ranking_ms,payload_json) VALUES(?,'legacy',?,?,0,0,0,?)",
                article.id(),"b".repeat(64),article.contentHash(),JSON.writeValueAsString(article));
        database.update("INSERT INTO news_github_snapshot(source_id,repository_id,bucket_ms,observed_ms,full_name,stars,forks,payload_json) VALUES('legacy',1,0,0,'owner/repo',1,1,?)",JSON.writeValueAsString(snapshot(1,NOW)));
        database.update("INSERT INTO news_source_withdrawal(source_id,external_id,observed_ms) VALUES('legacy','gone',0)");
    }
    static void assertMigratedUsage(JdbcTemplate database,String original) {
        assertThat(database.queryForObject("SELECT article_rows+snapshot_rows+withdrawal_rows FROM news_collection_capacity",Long.class)).isEqualTo(3);
        assertThat(database.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class)).isEqualTo(database.queryForObject(
                "SELECT (SELECT OCTET_LENGTH(payload_json)+128 FROM news_article)+(SELECT OCTET_LENGTH(payload_json) FROM news_github_snapshot)",Long.class));
        assertThat(database.queryForObject("SELECT payload_json FROM news_article",String.class)).isEqualTo(original);
        constraintRejected(()->database.update("INSERT INTO news_article(id,source_id,canonical_hash,content_hash,published_ms,fetched_ms,payload_json) "
                +"SELECT ?,source_id,?,content_hash,published_ms,fetched_ms,payload_json FROM news_article","c".repeat(64),"d".repeat(64)),"chk_news_article_capacity");
        constraintRejected(()->database.update("INSERT INTO news_source_withdrawal(source_id,external_id,observed_ms) VALUES('legacy','old-writer',1)"),"chk_news_withdrawal_capacity");
    }
    static void concurrentCleanup(JdbcTemplate database) throws Exception {
        var limits=new CollectionCapacityProperties(100,100,100,1_000_000);
        var repository=repository(database,limits);repository.saveSource(Fixtures.source("clean",Category.TECH));
        Instant old=NOW.minus(Duration.ofDays(91));
        for(int index=0;index<12;index++)repository.ingest("clean",List.of(input("old-"+index,old)),old);
        var cleanup=new RetentionRepository(database,JSON,Fixtures.transaction(database),new RetentionProperties(true,168,90,180,90,2),new CollectionCapacityRepository(database,limits));
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<?> writing=pool.submit(()->{for(int index=0;index<12;index++)repository.ingest("clean",List.of(input("new-"+index,NOW)),NOW);});
            Future<?> cleaning=pool.submit(()->{for(int index=0;index<8;index++)cleanup.sweep(NOW);});
            writing.get(30,TimeUnit.SECONDS);cleaning.get(30,TimeUnit.SECONDS);
        }
        assertThat(database.queryForObject("SELECT article_rows FROM news_collection_capacity",Long.class)).isEqualTo(12);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM news_article",Long.class)).isEqualTo(12);
        assertThat(database.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class))
                .isEqualTo(database.queryForObject("SELECT SUM(capacity_bytes) FROM news_article",Long.class));
    }
    static void concurrentAdmission(JdbcTemplate database) throws Exception {
        var limits=new CollectionCapacityProperties(1,1,1,1_000_000);
        var first=repository(database,limits);var second=repository(database,limits);
        for(int index=0;index<8;index++)first.saveSource(Fixtures.source("parallel-"+index,Category.TECH));
        CountDownLatch ready=new CountDownLatch(8),start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> jobs=new ArrayList<>();
            for(int index=0;index<8;index++) {
                int selected=index;
                jobs.add(pool.submit(()->{
                    ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture barrier");
                    try { (selected%2==0?first:second).ingest("parallel-"+selected,List.of(input("parallel-"+selected,NOW)),NOW);return true; }
                    catch(NewsException exception){assertThat(exception.code()).isEqualTo(ErrorCode.NEWS_028);return false;}
                }));
            }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();int accepted=0;
            for(var job:jobs)if(job.get(20,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(database.queryForObject("SELECT article_rows FROM news_collection_capacity",Long.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM news_article",Long.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT payload_bytes FROM news_collection_capacity",Long.class))
                .isEqualTo(database.queryForObject("SELECT SUM(capacity_bytes) FROM news_article",Long.class));
    }
    static NewsRepository repository(JdbcTemplate database,CollectionCapacityProperties limits) {
        var capacity=new CollectionCapacityRepository(database,limits);var tx=Fixtures.transaction(database);
        return new NewsRepository(database,JSON,tx,new GithubSnapshotRepository(database,JSON,capacity,tx),capacity, Fixtures.control(database), Fixtures.registry(database));
    }
    private RetentionRepository retention(CollectionCapacityProperties policy) {
        return new RetentionRepository(jdbc,JSON,Fixtures.transaction(jdbc),new RetentionProperties(true,168,90,180,90,250),new CollectionCapacityRepository(jdbc,policy));
    }
    private void source(String id,SourceKind kind,String url) {
        news.saveSource(new SourceSpec(id,id,url,Category.TECH,id,null,EvidencePolicy.METADATA_ONLY,"fixture-only",0.8,true,kind));
    }
    private static ArticleInput input(String id,Instant now){return new ArticleInput("https://example.com/"+id,"Event "+id,now.minusSeconds(1),Fixtures.EVIDENCE,0);}
    private static GithubSnapshot snapshot(long id,Instant now){return new GithubSnapshot(id,"owner/repo"+id,"https://github.com/owner/repo"+id,now.minus(Duration.ofDays(30)),now,"Evidence",10,2,now);}
    private static Map<String,Object> usage(JdbcTemplate database){return database.queryForMap("SELECT * FROM news_collection_capacity");}
    private static void rejected(Runnable operation){assertThatThrownBy(operation::run).isInstanceOfSatisfying(NewsException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.NEWS_028));}
    private static void constraintRejected(Runnable operation,String constraint) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,error->{
            // MySQL 的 CHECK 错误使用 HY000/3819，不能要求 Spring 与 H2 归入相同异常子类。
            assertThat(error.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->{
                assertThat(sql.getErrorCode()).isIn(3819,23513);
                assertThat(sql.getMessage()).containsIgnoringCase(constraint);
            });
        });
    }
    private static void assertEmpty(JdbcTemplate database) {
        assertThat(database.queryForObject("SELECT article_rows+snapshot_rows+withdrawal_rows+payload_bytes FROM news_collection_capacity",Long.class)).isZero();
        for(String table:List.of("news_article","news_github_snapshot","news_source_withdrawal"))assertThat(database.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
    }
}
