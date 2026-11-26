package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.*;
import com.yuyutian.mytools.news.controller.NewsOperationsController;
import com.yuyutian.mytools.news.mapper.*;
import com.yuyutian.mytools.news.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RetentionTest {
    private static final Instant NOW=Fixtures.CUTOFF;
    private JdbcTemplate jdbc;
    private ObjectMapper json;
    private TransactionTemplate tx;
    private NewsRepository news;
    private GithubSnapshotRepository github;
    private RetentionRepository retention;
    private final RetentionProperties policy=new RetentionProperties(true,168,90,180,90,2);
    @BeforeEach void setup() {
        JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:mem:retention-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(data).load().migrate();jdbc=new JdbcTemplate(data);json=new ObjectMapper().findAndRegisterModules();
        tx=new TransactionTemplate(new DataSourceTransactionManager(data));github=new GithubSnapshotRepository(jdbc,json, Fixtures.capacity(jdbc), Fixtures.transaction(jdbc));
        news=new NewsRepository(jdbc,json,tx,github, Fixtures.capacity(jdbc), Fixtures.control(jdbc), Fixtures.registry(jdbc));retention=new RetentionRepository(jdbc,json,tx,policy, Fixtures.capacity(jdbc));
        news.saveSource(Fixtures.source("source",Category.TECH));
    }
    @Test void evidenceExpiresButIdentityUrlHashAndMetadataRemain() {
        ingest("one",NOW.minusSeconds(10*3600));retention.sourcePolicy("source",8,NOW);
        Article before=news.candidates(NOW).getFirst();
        assertThat(retention.preview(NOW).get("articleEvidence")).isEqualTo(1);
        assertThat(news.candidates(NOW).getFirst().evidenceText()).isNotEmpty();
        assertThat(retention.sweep(NOW).get("articleEvidence")).isEqualTo(1);
        Article after=news.candidates(NOW).getFirst();
        assertThat(after.evidenceText()).isEmpty();assertThat(after.evidenceExpiredAt()).isEqualTo(NOW);
        assertThat(after.id()).isEqualTo(before.id());assertThat(after.url()).isEqualTo(before.url());assertThat(after.contentHash()).isEqualTo(before.contentHash());
        Edition edition=new DigestEngine().build(UUID.randomUUID(),7,NOW,NOW,ZoneOffset.UTC,RegionSnapshot.unresolved(),List.of(after));
        assertThat(edition.sections().get(Category.TECH).items().getFirst().detailStatus()).isEqualTo(DetailStatus.EVIDENCE_EXPIRED);
        ingest("one",NOW.minusSeconds(10*3600));assertThat(news.candidates(NOW).getFirst().evidenceText()).isEmpty();
        assertThat(retention.sweep(NOW).get("articleEvidence")).isZero();
    }
    @Test void sevenDayGithubMetricsSurviveDescriptionCompaction() {
        Instant old=NOW.minus(Duration.ofDays(7)).minusSeconds(3600);
        github.save("source",new GithubSnapshot(1,"owner/repo","https://example.com/repo",old,old,"Evidence description",20,3,old));
        assertThat(retention.sweep(NOW).get("githubEvidence")).isEqualTo(1);
        GithubSnapshot saved=github.history("source",1,NOW).getFirst();
        assertThat(saved.description()).isEmpty();assertThat(saved.stars()).isEqualTo(20);assertThat(saved.observedAt()).isEqualTo(old);
    }
    @Test void oldEditionBecomesGoneWithoutReusingItsKeyOrLeakingOwner() {
        Edition edition=edition(NOW.minus(Duration.ofDays(181)),"old");
        assertThat(retention.sweep(NOW).get("editions")).isEqualTo(1);
        assertCode(()->news.edition(7,edition.id()),ErrorCode.NEWS_026);
        assertCode(()->news.findByKey(7,"old","request"),ErrorCode.NEWS_026);
        assertCode(()->news.findByKey(7,"old","changed"),ErrorCode.NEWS_004);
        assertCode(()->news.edition(8,edition.id()),ErrorCode.NEWS_003);
        assertThat(news.history(7,100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition",Integer.class)).isEqualTo(1);
    }
    @Test void pendingUnknownLeasesAndRetryIntentPreventUnsafeCompaction() {
        Edition edition=edition(NOW.minus(Duration.ofDays(181)),"retained");
        String unknown=part("UNKNOWN",edition.id(),false,false);
        String leased=part("FAILED",null,true,false);
        String retrying=part("FAILED",null,false,true);
        String pending=part("PENDING",null,false,false);
        retention.sweep(NOW);
        for(String id:List.of(unknown,leased,retrying,pending))assertThat(jdbc.queryForObject("SELECT content_json FROM news_dispatch_outbox WHERE id=?",String.class,id)).isNotNull();
        assertThat(news.edition(7,edition.id())).isEqualTo(edition);
    }
    @Test void editionWithKnownFailureStillWaitsForRetryIntentAndLiveLease() {
        Edition retrying=edition(NOW.minus(Duration.ofDays(181)),"pending-retry");
        Edition leased=edition(NOW.minus(Duration.ofDays(181)),"pending-lease");
        part("FAILED",retrying.id(),false,true);part("FAILED",leased.id(),true,false);
        assertThat(retention.sweep(NOW).get("editions")).isZero();
        assertThat(news.edition(7,retrying.id())).isEqualTo(retrying);
        assertThat(news.edition(7,leased.id())).isEqualTo(leased);
    }
    @Test void finishedExpiredPartsReleaseBodyButKeepOriginalOutcomeAndIdentity() {
        String id=part("FAILED",null,false,false);
        assertThat(retention.sweep(NOW).get("deliveryBodies")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_json FROM news_dispatch_outbox WHERE id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM news_dispatch_outbox WHERE id=?",String.class,id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM news_dispatch_outbox WHERE id=?",String.class,id)).isEqualTo("a".repeat(64));
        // 即使后续取消把可处理时间提前，已压缩的片段也不能再次领取。
        jdbc.update("UPDATE news_dispatch_outbox SET available_ms=0 WHERE id=?",id);
        DispatchRepository dispatch=new DispatchRepository(jdbc,tx.getTransactionManager(),json,new DeliveryProperties(false,"http://localhost","",""),new NewsDeliveryRenderer(json,new DeliveryProperties(false,"http://localhost","","")), Fixtures.control(jdbc));
        assertThat(dispatch.claim(NOW)).isEmpty();
    }
    @Test void leaseAcquiredBetweenSelectionAndCompactionKeepsBody() {
        String id=part("FAILED",null,false,false);
        JdbcTemplate watched=org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doAnswer(call->{
            Object value=call.callRealMethod();
            String sql=call.getArgument(0);
            if(sql.startsWith("SELECT p.id FROM news_dispatch_outbox")) {
                jdbc.update("UPDATE news_dispatch_outbox SET lease_until_ms=? WHERE id=?",NOW.plusSeconds(60).toEpochMilli(),id);
            }
            return value;
        }).when(watched).queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(String.class),org.mockito.ArgumentMatchers.any(Object[].class));
        RetentionRepository racing=new RetentionRepository(watched,json,tx,policy, Fixtures.capacity(watched));
        assertThat(racing.sweep(NOW).get("deliveryBodies")).isZero();
        assertThat(jdbc.queryForObject("SELECT content_json FROM news_dispatch_outbox WHERE id=?",String.class,id)).isNotNull();
    }
    @Test void fileDatabaseReopenKeepsCompactionAndIdempotencyCheckpoint(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        JdbcDataSource disk=new JdbcDataSource();disk.setURL("jdbc:h2:file:"+directory.resolve("retention")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(disk).load().migrate();
        JdbcTemplate first=new JdbcTemplate(disk);TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(disk));
        NewsRepository before=new NewsRepository(first,json,transaction,new GithubSnapshotRepository(first,json, Fixtures.capacity(first), Fixtures.transaction(first)), Fixtures.capacity(first), Fixtures.control(first), Fixtures.registry(first));
        Instant old=NOW.minus(Duration.ofDays(181));
        Edition edition=new DigestEngine().build(UUID.randomUUID(),7,old,old,ZoneOffset.UTC,RegionSnapshot.unresolved(),List.of());
        before.saveEdition(edition,"disk-key","request");
        new RetentionRepository(first,json,transaction,policy, Fixtures.capacity(first)).sweep(NOW);first.execute("SHUTDOWN");
        JdbcDataSource reopened=new JdbcDataSource();reopened.setURL(disk.getURL());
        JdbcTemplate second=new JdbcTemplate(reopened);TransactionTemplate nextTx=new TransactionTemplate(new DataSourceTransactionManager(reopened));
        NewsRepository after=new NewsRepository(second,json,nextTx,new GithubSnapshotRepository(second,json, Fixtures.capacity(second), Fixtures.transaction(second)), Fixtures.capacity(second), Fixtures.control(second), Fixtures.registry(second));
        assertCode(()->after.findByKey(7,"disk-key","request"),ErrorCode.NEWS_026);
        assertThat(new RetentionRepository(second,json,nextTx,policy, Fixtures.capacity(second)).checkpoint().get("last_status")).isEqualTo("SUCCEEDED");
        assertThat(second.queryForObject("SELECT COUNT(*) FROM news_edition",Integer.class)).isEqualTo(1);
        second.execute("SHUTDOWN");
    }
    @Test void batchLimitBoundsWorkAndRepeatedRunsConverge() {
        for(int i=0;i<5;i++)ingest("old-"+i,NOW.minus(Duration.ofDays(91)));
        assertThat(retention.sweep(NOW).get("articleMetadata")).isEqualTo(2);
        assertThat(retention.sweep(NOW).get("articleMetadata")).isEqualTo(2);
        assertThat(retention.sweep(NOW).get("articleMetadata")).isEqualTo(1);
        assertThat(retention.preview(NOW).get("articleMetadata")).isZero();
    }
    @Test void failedBatchRollsBackEarlierBodyWritesAndReleasesLease() {
        ingest("good",NOW.minus(Duration.ofDays(8)));ingest("bad",NOW.minus(Duration.ofDays(8)).plusSeconds(1));
        jdbc.update("UPDATE news_article SET payload_json='broken' WHERE fetched_ms=?",NOW.minus(Duration.ofDays(8)).plusSeconds(1).toEpochMilli());
        assertCode(()->retention.sweep(NOW),ErrorCode.NEWS_009);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_article WHERE evidence_expired_ms IS NOT NULL",Integer.class)).isZero();
        assertThat(retention.checkpoint().get("last_status")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM news_retention_state",String.class)).isNull();
    }
    @Test void liveLeasePreventsOverlapAndExpiredLeaseIsRecoverable() {
        jdbc.update("UPDATE news_retention_state SET lease_token='other',lease_until_ms=?",NOW.plusSeconds(30).toEpochMilli());
        assertThat(retention.sweep(NOW)).isEmpty();
        assertThat(retention.sweep(NOW.plusSeconds(31))).isNotEmpty();
        assertThat(retention.checkpoint().get("last_status")).isEqualTo("SUCCEEDED");
    }
    @Test void disabledCleanupStillSupportsReadOnlyPreview() {
        RetentionRepository disabled=new RetentionRepository(jdbc,json,tx,new RetentionProperties(false,168,90,180,90,2), Fixtures.capacity(jdbc));
        ingest("one",NOW.minus(Duration.ofDays(8)));
        assertThat(disabled.preview(NOW).get("articleEvidence")).isEqualTo(1);
        assertCode(()->disabled.sweep(NOW),ErrorCode.NEWS_027);
        assertThat(jdbc.queryForObject("SELECT evidence_expired_ms FROM news_article",Long.class)).isNull();
    }
    @Test void sourceOverrideCannotExtendGlobalPolicyOrTargetMissingSource() {
        assertCode(()->retention.sourcePolicy("source",169,NOW),ErrorCode.NEWS_002);
        assertCode(()->retention.sourcePolicy("missing",1,NOW),ErrorCode.NEWS_003);
        retention.sourcePolicy("source",24,NOW);retention.sourcePolicy("source",1,NOW);
        assertThat(jdbc.queryForObject("SELECT evidence_hours FROM news_source_retention",Integer.class)).isEqualTo(1);
    }
    @Test void adminOnlyOperationsAndMetricsExposeAggregatesWithoutPayloads() throws Exception {
        String partId=part("UNKNOWN",null,false,false);
        SimpleMeterRegistry meters=new SimpleMeterRegistry();
        try {
            NewsOperations operations=new NewsOperations(jdbc,retention,policy,Clock.fixed(NOW,ZoneOffset.UTC),meters,new CollectionCapacityProperties(250000,250000,100000,536870912),Fixtures.control(jdbc),new ControlCapacityProperties(1000,1000,100000,100000),Fixtures.registry(jdbc),new RegistryCapacityProperties(1000,10000,1000,100000,10000));
            String status=json.writeValueAsString(operations.status());
            assertThat(status).contains("delivery_unknown\":1").doesNotContain(partId,"Frozen content");
            assertThat(meters.get("news.operations.delivery_unknown").gauge().value()).isEqualTo(1);
            var mvc=MockMvcBuilders.standaloneSetup(new NewsOperationsController(retention,operations,Clock.fixed(NOW,ZoneOffset.UTC)))
                .addFilters(new InternalTokenFilter(new NewsProperties("proxy","admin",false,ZoneOffset.UTC,LocalTime.NOON))).build();
            mvc.perform(get("/internal/admin/operations").header("Authorization","Bearer proxy")).andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/admin/operations").header("Authorization","Bearer admin")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"));
        } finally { meters.close(); }
    }
    @Test void databaseFailureProducesAvailabilityZeroAndDoesNotRepeatEveryGaugeQuery() {
        JdbcTemplate unavailable=org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(unavailable.queryForObject(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(Long.class),org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database detail"));
        SimpleMeterRegistry meters=new SimpleMeterRegistry();
        try {
            new NewsOperations(unavailable,retention,policy,Clock.fixed(NOW,ZoneOffset.UTC),meters,new CollectionCapacityProperties(250000,250000,100000,536870912),Fixtures.control(jdbc),new ControlCapacityProperties(1000,1000,100000,100000),Fixtures.registry(jdbc),new RegistryCapacityProperties(1000,10000,1000,100000,10000));
            assertThat(meters.get("news.operations.database_available").gauge().value()).isZero();
            assertThat(meters.get("news.operations.delivery_unknown").gauge().value()).isNaN();
            assertThat(meters.get("news.operations.task_dead").gauge().value()).isNaN();
            org.mockito.Mockito.verify(unavailable,org.mockito.Mockito.times(1)).queryForObject(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(Long.class),org.mockito.ArgumentMatchers.any(Object[].class));
        } finally {meters.close();}
    }
    private void ingest(String id,Instant observed){news.ingest("source",List.of(new ArticleInput("https://example.com/"+id,"Event "+id,observed.minusSeconds(1),Fixtures.EVIDENCE,0)),observed);}
    private Edition edition(Instant created,String key){Edition value=new DigestEngine().build(UUID.randomUUID(),7,created,created,ZoneOffset.UTC,RegionSnapshot.unresolved(),List.of());return news.saveEdition(value,key,"request");}
    private String part(String state,UUID edition,boolean leased,boolean retry) {
        String batch=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();long old=NOW.minus(Duration.ofDays(100)).toEpochMilli();
        jdbc.update("INSERT INTO news_delivery_batch(id,run_id,subscription_id,owner_id,target_revision,destination_id,channel,render_mode,edition_id,state,expires_ms,created_ms,updated_ms) VALUES(?,?,?,7,1,?,'EMAIL','FULL',?,'QUEUED',?,?,?)",batch,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),edition==null?null:edition.toString(),old,old,old);
        jdbc.update("INSERT INTO news_dispatch_outbox(id,batch_id,part_index,part_count,content_json,payload_hash,status,available_ms,updated_ms,lease_until_ms,retry_generation) VALUES(?,?,1,1,?,?,?,0,?,?,?)",id,batch,"{\"subject\":\"Frozen content\",\"text\":\"body\",\"html\":null}","a".repeat(64),state,old,leased?NOW.plusSeconds(10).toEpochMilli():null,retry?1:null);
        return id;
    }
    private void assertCode(Runnable action,ErrorCode code){assertThatThrownBy(action::run).isInstanceOfSatisfying(NewsException.class,e->assertThat(e.code()).isEqualTo(code));}
}
