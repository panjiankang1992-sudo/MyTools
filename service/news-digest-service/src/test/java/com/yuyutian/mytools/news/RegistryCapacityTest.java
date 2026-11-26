package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.Model.RegionModels.*;
import com.yuyutian.mytools.news.Model.SchedulingModels.ExecutionGrant;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.RegistryCapacityProperties;
import com.yuyutian.mytools.news.mapper.*;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class RegistryCapacityTest {
    private static final Instant NOW=Fixtures.CUTOFF;
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private Harness h;
    @BeforeEach void setup(){var data=data();Flyway.configure().dataSource(data).load().migrate();jdbc=new JdbcTemplate(data);h=new Harness(jdbc);}
    @Test void sourceReplayAndDisableRemainAvailableAtCapacity() {
        SourceSpec source=Fixtures.source("one",Category.WORLD);h.news.saveSource(source);
        rejected(()->h.news.saveSource(Fixtures.source("two",Category.WORLD)));
        assertThat(h.news.saveSource(source)).isEqualTo(source);
        SourceSpec disabled=new SourceSpec(source.id(),source.name(),source.feedUrl(),source.category(),source.publisherKey(),source.regionCode(),source.evidencePolicy(),source.rightsReference(),source.authority(),false,source.kind());
        assertThat(h.news.saveSource(disabled).enabled()).isFalse();assertThat(h.news.saveSource(source).enabled()).isTrue();audit();
    }
    @Test void packReplayRetainsOriginalEffectiveTimeAndCannotOverwriteVersion() {
        RegionSourcePack pack=pack(1,"Fixture");h.regions.savePack(pack,NOW);
        assertThat(h.regions.savePack(pack,NOW.plusSeconds(600))).isEqualTo(pack);
        rejected(()->h.regions.savePack(pack(2,"New"),NOW.plusSeconds(600)));
        code(()->h.regions.savePack(pack(1,"Changed"),NOW),ErrorCode.NEWS_004);
        assertThat(h.regions.pack("CN-GD",NOW)).contains(pack);assertThat(h.regions.pack("CN-GD",NOW.minusMillis(1))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT registered_ms FROM news_region_source_pack",Long.class)).isEqualTo(NOW.toEpochMilli());audit();
    }
    @Test void directObservationReplayDoesNotChangeEvidenceTimeOrUseAnotherSlot() {
        RegionObservation first=observation(UUID.randomUUID(),"node");h.regions.saveObservation(first,NOW);h.regions.saveObservation(first,NOW.plusSeconds(10));
        rejected(()->h.regions.saveObservation(observation(UUID.randomUUID(),"node"),NOW));
        code(()->h.regions.saveObservation(observation(first.id(),"another"),NOW),ErrorCode.NEWS_004);
        assertThat(jdbc.queryForObject("SELECT received_ms FROM news_region_observation",Long.class)).isEqualTo(NOW.toEpochMilli());audit();
    }
    @Test void acceptedJobCanFinishAndReplayWhenEveryLimitIsFull(){acceptedJobFinishes(jdbc);}
    @Test void rejectedJobRollsBackNewNodeAndOutbox() {
        UUID original=h.jobs.scan("node",NOW).orElseThrow();h.jobs.cancel(original,NOW);
        var largerNodes=new Harness(jdbc,new RegistryCapacityProperties(1,1,2,1,1));
        rejected(()->largerNodes.jobs.scan("new-node",NOW.plusSeconds(21600)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_schedule",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_task_outbox",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT region_jobs FROM news_registry_capacity",Long.class)).isEqualTo(1);audit();
    }
    @Test void jobSlotCannotBeBorrowedByAnotherNodeOrSecondReport() {
        UUID id=h.jobs.scan("node",NOW).orElseThrow();RegionObservation first=observation(UUID.randomUUID(),"node");
        code(()->h.regions.saveObservation(observation(UUID.randomUUID(),"other"),NOW,id),ErrorCode.NEWS_016);
        code(()->h.regions.saveObservation(first,NOW,UUID.randomUUID()),ErrorCode.NEWS_016);
        h.regions.saveObservation(first,NOW,id);
        code(()->h.regions.saveObservation(observation(UUID.randomUUID(),"node"),NOW,id),ErrorCode.NEWS_004);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_observation",Long.class)).isEqualTo(1);audit();
    }
    @Test void directReportCannotBeReusedAsAnUnrelatedJobResult() {
        RegionObservation first=observation(UUID.randomUUID(),"node");h.regions.saveObservation(first,NOW);UUID id=h.jobs.scan("node",NOW).orElseThrow();
        code(()->h.regions.saveObservation(first,NOW,id),ErrorCode.NEWS_004);
        assertThat(jdbc.queryForObject("SELECT region_job_id FROM news_region_observation",String.class)).isNull();audit();
    }
    @Test void cancelledJobKeepsIdentityButCannotAcceptNewObservation() {
        UUID id=h.jobs.scan("node",NOW).orElseThrow();assertThat(h.jobs.cancel(id,NOW).status()).isEqualTo("CANCELLED");
        code(()->h.regions.saveObservation(observation(UUID.randomUUID(),"node"),NOW,id),ErrorCode.NEWS_016);
        assertThat(h.jobs.scan("node",NOW)).isEmpty();rejected(()->h.jobs.scan("node",NOW.plusSeconds(21600)));audit();
    }
    @Test void failureAfterReportSaveRollsBackReportAndKeepsReservationReusable() {
        UUID id=h.jobs.scan("node",NOW).orElseThrow();RegionObservation report=observation(UUID.randomUUID(),"node");ExecutionGrant grant=grant(id,"node");
        assertThatThrownBy(()->h.jobs.complete(id,grant,report,()->grant,()->{h.regions.saveObservation(report,NOW,id);throw new IllegalStateException("fixture-failure");},()->NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(h.regions.observation(report.id())).isEmpty();assertThat(h.jobs.job(id).status()).isEqualTo("QUEUED");
        assertThat(h.jobs.complete(id,grant,report,()->grant,()->h.regions.saveObservation(report,NOW,id),()->NOW).status()).isEqualTo("STORED");audit();
    }
    @Test void outerRollbackRestoresAllReservationsAndNoEmptyCursorSurvives() {
        assertThatThrownBy(()->h.tx.executeWithoutResult(status->{h.news.saveSource(Fixtures.source("one",Category.WORLD));h.jobs.scan("node",NOW);throw new IllegalStateException("fixture-failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(h.news.sources()).isEmpty();assertThat(h.jobs.jobs()).isEmpty();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_schedule",Long.class)).isZero();audit();
    }
    @Test void concurrentSourcesAndObservationsUseOnlyLastAvailableSlots() throws Exception {concurrentRegistrations(jdbc);}
    @Test void concurrentJobsReserveOneNodeAndOneReportSlot() throws Exception {concurrentJobs(jdbc);}
    @Test void newPayloadBoundsPreventUnboundedTextInSingleRows() {
        RegionObservation large=new RegionObservation(UUID.randomUUID(),"node",NOW,ObservationStatus.EGRESS_UNAVAILABLE,null,null,"DIRECT","MAXMIND_MMDB","\u65b0".repeat(3000),null);
        code(()->h.regions.saveObservation(large,NOW),ErrorCode.NEWS_002);
        code(()->h.regions.savePack(pack(1,"\u65b0".repeat(12000)),NOW),ErrorCode.NEWS_002);
        SourceSpec source=new SourceSpec("one","\u65b0".repeat(12000),"https://example.test/feed",Category.WORLD,"one",null,EvidencePolicy.METADATA_ONLY,"fixture-only",0.5,true);
        code(()->h.news.saveSource(source),ErrorCode.NEWS_002);
        assertThat(jdbc.queryForObject("SELECT source_rows+pack_rows+direct_observations FROM news_registry_capacity",Long.class)).isZero();audit();
    }
    @Test void oldUnmeteredWritesAndOrphanPollCursorsAreRejected() {
        assertThatThrownBy(()->jdbc.update("INSERT INTO news_source(id,spec_json,enabled,health) VALUES('old','{}',TRUE,'FRESH')")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->new SourcePollingRepository(jdbc).register("missing",NOW)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        audit();
    }
    @Test void readOnlyAuditDetectsDriftWithoutResettingOrLeakingReport() throws Exception {
        h.regions.saveObservation(observation(UUID.randomUUID(),"node"),NOW);jdbc.update("UPDATE news_registry_capacity SET direct_observations=2");
        assertThat(h.capacity.status()).containsEntry("accountingMatches",false);
        assertThat(JSON.writeValueAsString(h.capacity.status())).doesNotContain("MAXMIND_MMDB","nodeId","fixture-only");
        assertThat(jdbc.queryForObject("SELECT direct_observations FROM news_registry_capacity",Long.class)).isEqualTo(2);
    }
    @Test void populatedV9MigrationPreservesReportsAndAdmitsPendingJobAtFullQuota() throws Exception {
        JdbcDataSource data=data();Flyway.configure().dataSource(data).target("9").load().migrate();JdbcTemplate database=new JdbcTemplate(data);
        seedLegacy(database);var before=database.queryForList("SELECT id,payload_json,received_ms FROM news_region_observation ORDER BY id");
        assertThat(Flyway.configure().dataSource(data).load().migrate().migrationsExecuted).isEqualTo(2);
        assertThat(database.queryForList("SELECT id,payload_json,received_ms FROM news_region_observation ORDER BY id")).isEqualTo(before);
        assertLegacy(database);
    }
    static void acceptedJobFinishes(JdbcTemplate jdbc) {
        Harness h=new Harness(jdbc);h.news.saveSource(Fixtures.source("one",Category.WORLD));h.regions.savePack(pack(1,"Fixture"),NOW);
        h.regions.saveObservation(observation(UUID.randomUUID(),"node"),NOW);UUID job=h.jobs.scan("node",NOW).orElseThrow();
        RegionObservation report=observation(UUID.randomUUID(),"node");ExecutionGrant grant=grant(job,"node");
        var first=h.jobs.complete(job,grant,report,()->grant,()->h.regions.saveObservation(report,NOW,job),()->NOW);
        assertThat(h.jobs.complete(job,grant,report,()->grant,()->h.regions.saveObservation(report,NOW.plusSeconds(1),job),()->NOW)).isEqualTo(first);
        rejected(()->h.regions.saveObservation(observation(UUID.randomUUID(),"node"),NOW));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_observation",Long.class)).isEqualTo(2);
        assertThat(h.capacity.status()).containsEntry("accountingMatches",true);
    }
    static void concurrentRegistrations(JdbcTemplate jdbc) throws Exception {
        Harness h=new Harness(jdbc);
        assertThat(parallel(index->{try{h.news.saveSource(Fixtures.source("source-"+index,Category.WORLD));return true;}catch(NewsException e){assertThat(e.code()).isEqualTo(ErrorCode.NEWS_030);return false;}}).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        SourceSpec first=h.news.sources().getFirst().spec();assertThat(parallel(index->h.news.saveSource(first).equals(first))).containsOnly(true);
        assertThat(parallel(index->{try{h.regions.saveObservation(observation(UUID.randomUUID(),"node"),NOW);return true;}catch(NewsException e){assertThat(e.code()).isEqualTo(ErrorCode.NEWS_030);return false;}}).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(h.capacity.status()).containsEntry("accountingMatches",true);
    }
    static void concurrentJobs(JdbcTemplate jdbc) throws Exception {
        Harness h=new Harness(jdbc);
        List<Boolean> results=parallel(index->{try{return h.jobs.scan("node-"+index,NOW).isPresent();}catch(NewsException e){assertThat(e.code()).isEqualTo(ErrorCode.NEWS_030);return false;}});
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        String node=h.jobs.jobs().getFirst().nodeId();assertThat(parallel(index->h.jobs.scan(node,NOW).isEmpty())).containsOnly(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_schedule",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_task_outbox WHERE job_kind='REGION'",Long.class)).isEqualTo(1);
        assertThat(h.capacity.status()).containsEntry("accountingMatches",true);
    }
    static void seedLegacy(JdbcTemplate jdbc) throws Exception {
        SourceSpec source=Fixtures.source("legacy",Category.WORLD);jdbc.update("INSERT INTO news_source(id,spec_json,enabled,health) VALUES('legacy',?,TRUE,'FRESH')",JSON.writeValueAsString(source));
        jdbc.update("INSERT INTO news_source_poll(source_id,next_due_ms) VALUES('legacy',0)");
        jdbc.update("INSERT INTO news_region_source_pack(region_code,version_number,registered_ms,payload_json) VALUES('CN-GD',1,0,?)",JSON.writeValueAsString(pack(1,"Fixture")));
        jdbc.update("INSERT INTO news_region_schedule(node_id) VALUES('node')");
        UUID stored=UUID.randomUUID(),pending=UUID.randomUUID(),report=UUID.randomUUID();
        for(UUID id:List.of(stored,pending)) {
            jdbc.update("INSERT INTO news_region_job(id,node_id,bucket_ms,expires_ms,status,observation_id,created_ms,updated_ms) VALUES(?,'node',?,?,?,?,0,0)",id.toString(),id.equals(stored)?0L:1L,NOW.plusSeconds(900).toEpochMilli(),id.equals(stored)?"STORED":"QUEUED",id.equals(stored)?report.toString():null);
            jdbc.update("INSERT INTO news_task_outbox(run_id,job_kind,state,available_ms) VALUES(?,'REGION','PENDING',0)",id.toString());
        }
        for(UUID id:List.of(report,UUID.randomUUID())) {
            RegionObservation value=observation(id,"node");String payload=JSON.writeValueAsString(value);
            jdbc.update("INSERT INTO news_region_observation(id,node_id,observed_ms,received_ms,status,payload_hash,payload_json) VALUES(?,'node',0,0,'EGRESS_UNAVAILABLE',?,?)",id.toString(),ContentIdentity.hash(payload),payload);
        }
    }
    static void assertLegacy(JdbcTemplate jdbc) {
        Harness h=new Harness(jdbc);assertThat(h.capacity.status()).containsEntry("accountingMatches",true);
        assertThat(jdbc.queryForObject("SELECT direct_observations FROM news_registry_capacity",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT region_jobs FROM news_registry_capacity",Long.class)).isEqualTo(2);
        UUID id=UUID.fromString(jdbc.queryForObject("SELECT id FROM news_region_job WHERE status='QUEUED'",String.class));RegionObservation report=observation(UUID.randomUUID(),"node");ExecutionGrant grant=grant(id,"node");
        assertThat(h.jobs.complete(id,grant,report,()->grant,()->h.regions.saveObservation(report,NOW,id),()->NOW).status()).isEqualTo("STORED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_observation WHERE capacity_kind='JOB'",Long.class)).isEqualTo(2);assertThat(h.capacity.status()).containsEntry("accountingMatches",true);
    }
    private static List<Boolean> parallel(java.util.function.IntFunction<Boolean> action) throws Exception {
        CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
        try(ExecutorService pool=Executors.newFixedThreadPool(8)) {
            for(int i=0;i<8;i++){int index=i;futures.add(pool.submit(()->{assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return action.apply(index);}));}
            start.countDown();List<Boolean> results=new ArrayList<>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;
        }
    }
    private void audit(){assertThat(h.capacity.status()).containsEntry("accountingMatches",true);}
    private static RegionObservation observation(UUID id,String node){return new RegionObservation(id,node,NOW,ObservationStatus.EGRESS_UNAVAILABLE,null,null,"DIRECT","MAXMIND_MMDB","fixture-only",null);}
    private static RegionSourcePack pack(int version,String label){return new RegionSourcePack("CN-GD",version,label,List.of("legacy"),NOW);}
    private static ExecutionGrant grant(UUID job,String node){return new ExecutionGrant(UUID.randomUUID(),job.toString(),"news:region:"+job,node,1,NOW.plusSeconds(60));}
    private static void rejected(Runnable action){code(action,ErrorCode.NEWS_030);}
    private static void code(Runnable action,ErrorCode code){assertThatThrownBy(action::run).isInstanceOfSatisfying(NewsException.class,e->assertThat(e.code()).isEqualTo(code));}
    private static JdbcDataSource data(){var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:registry-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");return data;}
    private static final class Harness {
        final RegistryCapacityRepository capacity;final NewsRepository news;final RegionRepository regions;final RegionJobRepository jobs;final TransactionTemplate tx;
        Harness(JdbcTemplate jdbc){this(jdbc,new RegistryCapacityProperties(1,1,1,1,1));}
        Harness(JdbcTemplate jdbc,RegistryCapacityProperties limits) {
            capacity=new RegistryCapacityRepository(jdbc,limits);tx=Fixtures.transaction(jdbc);
            news=new NewsRepository(jdbc,JSON,tx,new GithubSnapshotRepository(jdbc,JSON,Fixtures.capacity(jdbc),tx),Fixtures.capacity(jdbc),Fixtures.control(jdbc),capacity);
            regions=new RegionRepository(jdbc,JSON,Fixtures.control(jdbc),tx,capacity);jobs=new RegionJobRepository(jdbc,tx.getTransactionManager(),capacity);
        }
    }
}
