package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.*;
import com.yuyutian.mytools.news.mapper.*;
import com.yuyutian.mytools.news.service.*;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ControlCapacityTest {
    private static final Instant NOW=Fixtures.CUTOFF;
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private Harness h;
    @BeforeEach void setup() {
        var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:control-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        Flyway.configure().dataSource(data).load().migrate();jdbc=new JdbcTemplate(data);h=new Harness(jdbc);
    }
    @Test void previewRegionAndEditionShareOneIdentityAndReplayDoesNotNeedNewQuota() {
        RegionSnapshot snapshot=h.region.bind(55,"key","hash",NOW,NOW,RegionSnapshot::unresolved);
        Edition value=edition(55,snapshot);
        assertThat(h.news.saveEdition(value,"key","hash")).isEqualTo(value);
        assertThat(h.news.saveEdition(edition(55,snapshot),"key","hash")).isEqualTo(value);
        assertThat(h.region.bind(55,"key","hash",NOW,NOW,()->{throw new IllegalStateException("resolver must not run");})).isEqualTo(snapshot);
        rejected(()->h.region.bind(55,"another","hash",NOW,NOW,RegionSnapshot::unresolved));
        rejected(()->h.news.saveEdition(edition(56,snapshot),"key","hash"));
        assertThat(h.capacity.status().get("accountingMatches")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT preview_identities FROM news_control_capacity",Long.class)).isEqualTo(1);
    }
    @Test void directEditionSaveAlsoRequiresIdentityAndConflictingPayloadKeepsOriginal() {
        Edition first=h.news.saveEdition(edition(55,RegionSnapshot.unresolved()),"key","hash");
        assertCode(()->h.news.saveEdition(edition(55,RegionSnapshot.unresolved()),"key","changed"),ErrorCode.NEWS_004);
        assertThat(h.news.edition(55,first.id())).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_content_identity",Long.class)).isEqualTo(1);
    }
    @Test void generationFailureKeepsBoundRegionButFailedResolverRollsBackReservation() {
        assertThatThrownBy(()->h.region.bind(55,"failed","hash",NOW,NOW,()->{throw new IllegalStateException("fixture");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT preview_identities FROM news_control_capacity",Long.class)).isZero();
        h.region.bind(55,"key","hash",NOW,NOW,RegionSnapshot::unresolved);
        // 后续生成尚未完成时身份仍占用额度，另一个预览不能抢走重试所需的槽位。
        rejected(()->h.region.bind(55,"another","hash",NOW,NOW,RegionSnapshot::unresolved));
        h.news.saveEdition(edition(55,RegionSnapshot.unresolved()),"key","hash");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition",Long.class)).isEqualTo(1);
    }
    @Test void subscriptionAndTargetChangesRemainPossibleAtTheirLimits() {
        var subscription=h.scheduling.change(55,change(0,true),NOW.minusSeconds(1));
        rejected(()->h.scheduling.change(56,change(0,true),NOW.minusSeconds(1)));
        assertThat(h.scheduling.change(55,change(subscription.revision(),false),NOW).enabled()).isFalse();
        h.dispatch.changeTargets(55,0,List.of(target()),NOW);
        rejected(()->h.dispatch.changeTargets(56,0,List.of(target()),NOW));
        assertThat(h.dispatch.changeTargets(55,1,List.of(),NOW).targets()).isEmpty();
        assertThat(h.capacity.status().get("accountingMatches")).isEqualTo(true);
    }
    @Test void fullDailyQuotaLeavesNextDueAndOutboxUntouchedAndCancellationDoesNotFreeIdentity() {
        h.scheduling.change(55,change(0,true),NOW.minusSeconds(1));h.scheduling.scan(NOW,"node");
        var before=h.scheduling.subscription(55).orElseThrow();DailyRun run=h.scheduling.runs(55,1).getFirst();
        h.scheduling.cancel(55,run.id(),NOW);
        rejected(()->h.scheduling.scan(NOW.plus(Duration.ofDays(1)),"node"));
        assertThat(h.scheduling.subscription(55)).contains(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_task_outbox",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT daily_runs FROM news_control_capacity",Long.class)).isEqualTo(1);
    }
    @Test void acceptedRunFinishesAfterRunAndPreviewQuotasAreFull() { acceptedRunFinishes(jdbc); }
    @Test void scheduledKeysCannotBorrowAnotherOwnerOrForgeAReservation() {
        h.scheduling.change(55,change(0,true),NOW.minusSeconds(1));h.scheduling.scan(NOW,"node");
        DailyRun run=h.scheduling.runs(55,1).getFirst();String key="news-run:"+run.id();String hash=ContentIdentity.hash(NOW+"|UTC");
        assertCode(()->h.region.bind(56,key,hash,NOW,NOW,RegionSnapshot::unresolved),ErrorCode.NEWS_016);
        assertCode(()->h.region.bind(55,key,"wrong",NOW,NOW,RegionSnapshot::unresolved),ErrorCode.NEWS_016);
        assertCode(()->h.region.bind(55,"news-run:"+UUID.randomUUID(),hash,NOW,NOW,RegionSnapshot::unresolved),ErrorCode.NEWS_016);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_content_identity",Long.class)).isZero();
    }
    @Test void capturedTargetsCannotBeAddedLaterAndMalformedTargetSetRollsBack() {
        h.dispatch.changeTargets(55,0,List.of(target()),NOW);
        h.scheduling.change(55,change(0,true),NOW.minusSeconds(1));h.scheduling.scan(NOW,"node");
        DailyRun run=h.scheduling.runs(55,1).getFirst();
        h.dispatch.changeTargets(55,1,List.of(target()),NOW);
        Fixtures.transaction(jdbc).executeWithoutResult(status->h.dispatch.capture(run.id(),run.subscriptionId(),55,run.expiresAt(),NOW));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_delivery_batch",Long.class)).isEqualTo(1);
        assertCode(()->h.dispatch.changeTargets(55,2,java.util.stream.IntStream.range(0,6).mapToObj(i->target()).toList(),NOW),ErrorCode.NEWS_002);
        assertThat(h.dispatch.targets(55).revision()).isEqualTo(2);
    }
    @Test void initiallyDisabledDeliveryFreezesAnEmptyTargetSet() {
        Harness disabled=new Harness(jdbc,false);
        disabled.dispatch.changeTargets(55,0,List.of(target()),NOW);
        disabled.scheduling.change(55,change(0,true),NOW.minusSeconds(1));disabled.scheduling.scan(NOW,"node");
        DailyRun run=disabled.scheduling.runs(55,1).getFirst();
        Fixtures.transaction(jdbc).executeWithoutResult(status->h.dispatch.capture(run.id(),run.subscriptionId(),55,run.expiresAt(),NOW));
        assertThat(jdbc.queryForObject("SELECT delivery_captured FROM news_daily_run",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_delivery_batch",Long.class)).isZero();
    }
    @Test void captureFailureRollsBackAcceptedRunReservationAndDueDate() {
        h.dispatch.changeTargets(55,0,List.of(target()),NOW);
        // 模拟升级前的异常目标数据，验证接收事务不能留下已扣额度的半成品。
        for(int index=0;index<5;index++)jdbc.update("INSERT INTO news_subscription_target(owner_id,destination_id,channel,render_mode,enabled) VALUES(55,?,'EMAIL','FULL',TRUE)",UUID.randomUUID().toString());
        var subscription=h.scheduling.change(55,change(0,true),NOW.minusSeconds(1));
        assertCode(()->h.scheduling.scan(NOW,"node"),ErrorCode.NEWS_002);
        assertThat(h.scheduling.subscription(55)).contains(subscription);
        assertThat(jdbc.queryForObject("SELECT daily_runs FROM news_control_capacity",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_daily_run",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_task_outbox",Long.class)).isZero();
    }
    @Test void concurrentlyAcceptedPreviewKeysCannotExceedOneSlot() throws Exception { concurrentPreview(jdbc); }
    @Test void scheduledRegionBindingIsStableAcrossConcurrentRetries() throws Exception { concurrentScheduledBinding(jdbc); }
    @Test void outerRollbackReleasesNewSubscriptionAndTargetReservations() {
        assertThatThrownBy(()->Fixtures.transaction(jdbc).executeWithoutResult(status->{
            h.scheduling.change(55,change(0,true),NOW);h.dispatch.changeTargets(55,0,List.of(target()),NOW);
            throw new IllegalStateException("fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT subscription_rows+target_owners FROM news_control_capacity",Long.class)).isZero();
        assertThat(h.scheduling.subscription(55)).isEmpty();assertThat(h.dispatch.targets(55).revision()).isZero();
    }
    @Test void auditDoesNotResetTamperedCountersAndRetentionDoesNotReleaseKeys() {
        Edition value=h.news.saveEdition(edition(55,RegionSnapshot.unresolved()),"key","hash");
        jdbc.update("UPDATE news_edition SET payload_json=NULL,content_expired_ms=? WHERE id=?",NOW.toEpochMilli(),value.id().toString());
        assertCode(()->h.news.saveEdition(edition(55,RegionSnapshot.unresolved()),"key","hash"),ErrorCode.NEWS_026);
        rejected(()->h.news.saveEdition(edition(55,RegionSnapshot.unresolved()),"another","hash"));
        jdbc.update("UPDATE news_control_capacity SET preview_identities=2 WHERE id=1");
        assertThat(h.capacity.status().get("accountingMatches")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT preview_identities FROM news_control_capacity",Long.class)).isEqualTo(2);
    }
    @Test void populatedV8MigratesUnionIdentityOnceAndRetainsAcceptedRuns() throws Exception {
        var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:control-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(data).target("8").load().migrate();var database=new JdbcTemplate(data);
        seedLegacy(database);String before=database.queryForObject("SELECT payload_json FROM news_edition",String.class);
        assertThat(Flyway.configure().dataSource(data).target("9").load().migrate().migrationsExecuted).isEqualTo(1);
        assertLegacy(database,before);
    }
    static void acceptedRunFinishes(JdbcTemplate database) {
        Harness h=new Harness(database);
        h.region.bind(55,"preview","preview-hash",NOW,NOW,RegionSnapshot::unresolved);
        h.dispatch.changeTargets(55,0,List.of(target()),NOW);
        h.scheduling.change(55,change(0,true),NOW.minusSeconds(1));assertThat(h.scheduling.scan(NOW,"node")).isEqualTo(1);
        DailyRun run=h.scheduling.runs(55,1).getFirst();String key="news-run:"+run.id();String hash=ContentIdentity.hash(NOW+"|UTC");
        RegionSnapshot region=h.region.bind(55,key,hash,NOW,NOW,RegionSnapshot::unresolved);
        Edition edition=edition(55,region);
        var grant=new ExecutionGrant(UUID.randomUUID(),run.id().toString(),"news:"+run.id(),"node",1,NOW.plusSeconds(60));
        GenerationReceipt receipt=h.scheduling.publish(run.id(),grant,()->grant,()->h.news.saveEdition(edition,key,hash).id(),()->NOW);
        assertThat(receipt.status()).isEqualTo("READY");assertThat(receipt.editionId()).isEqualTo(edition.id());
        assertThat(database.queryForObject("SELECT COUNT(*) FROM news_dispatch_outbox",Long.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT daily_runs FROM news_control_capacity",Long.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT preview_identities FROM news_control_capacity",Long.class)).isEqualTo(1);
        assertThat(h.capacity.status().get("accountingMatches")).isEqualTo(true);
        assertThat(h.scheduling.publish(run.id(),grant,()->grant,()->{throw new IllegalStateException("must replay");},()->NOW)).isEqualTo(receipt);
    }
    static void concurrentPreview(JdbcTemplate database) throws Exception {
        Harness first=new Harness(database),second=new Harness(database);CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int index=0;index<8;index++) {int owner=index+1;results.add(pool.submit(()->{
                start.await();try{(owner%2==0?first:second).region.bind(owner,"key","hash",NOW,NOW,RegionSnapshot::unresolved);return true;}
                catch(NewsException error){assertThat(error.code()).isEqualTo(ErrorCode.NEWS_029);return false;}
            }));}
            start.countDown();int accepted=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(database.queryForObject("SELECT COUNT(*) FROM news_region_snapshot",Long.class)).isEqualTo(1);
        assertThat(first.capacity.status().get("accountingMatches")).isEqualTo(true);
    }
    static void concurrentScheduledBinding(JdbcTemplate database) throws Exception {
        Harness first=new Harness(database),second=new Harness(database);
        first.scheduling.change(55,change(0,true),NOW.minusSeconds(1));first.scheduling.scan(NOW,"node");
        DailyRun run=first.scheduling.runs(55,1).getFirst();String key="news-run:"+run.id(),hash=ContentIdentity.hash(NOW+"|UTC");
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<RegionSnapshot>> values=new ArrayList<>();
            for(int index=0;index<8;index++) {int n=index;values.add(pool.submit(()->{start.await();return(n%2==0?first:second).region.bind(55,key,hash,NOW,NOW,
                    ()->new RegionSnapshot("UNRESOLVED",null,"node-"+n,null));}));}
            start.countDown();RegionSnapshot expected=values.getFirst().get(20,TimeUnit.SECONDS);
            for(var value:values)assertThat(value.get(20,TimeUnit.SECONDS)).isEqualTo(expected);
        }
        assertThat(database.queryForObject("SELECT COUNT(*) FROM news_content_identity",Long.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT preview_identities FROM news_control_capacity",Long.class)).isZero();
    }
    static void seedLegacy(JdbcTemplate database) throws Exception {
        Edition edition=edition(55,RegionSnapshot.unresolved());String key=ContentIdentity.hash("legacy");
        database.update("INSERT INTO news_region_snapshot(owner_id,idempotency_hash,request_hash,cutoff_ms,created_ms,payload_json) VALUES(55,?,'hash',0,0,?)",key,JSON.writeValueAsString(edition.region()));
        database.update("INSERT INTO news_edition(id,owner_id,idempotency_hash,request_hash,cutoff_ms,created_ms,quality,payload_json) VALUES(?,55,?,'hash',0,0,'DEGRADED',?)",edition.id().toString(),key,JSON.writeValueAsString(edition));
        UUID subscription=UUID.randomUUID(),run=UUID.randomUUID();
        database.update("INSERT INTO news_subscription(id,owner_id,revision,enabled,zone_id,cutoff_time,updated_ms) VALUES(?,55,1,TRUE,'UTC','00:00',0)",subscription.toString());
        database.update("INSERT INTO news_delivery_settings(owner_id,revision,updated_ms) VALUES(55,1,0)");
        database.update("INSERT INTO news_daily_run(id,subscription_id,owner_id,subscription_revision,business_date,zone_id,cutoff_ms,expires_ms,skipped_days,node_id,status,created_ms,updated_ms) VALUES(?,?,55,1,'2026-10-18','UTC',?,?,0,'node','QUEUED',0,0)",run.toString(),subscription.toString(),NOW.toEpochMilli(),NOW.plusSeconds(3600).toEpochMilli());
        database.update("INSERT INTO news_task_outbox(run_id,state,available_ms) VALUES(?,'PENDING',0)",run.toString());
    }
    static void assertLegacy(JdbcTemplate database,String before) {
        assertThat(database.queryForObject("SELECT payload_json FROM news_edition",String.class)).isEqualTo(before);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM news_content_identity",Long.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT identity_kind FROM news_content_identity",String.class)).isEqualTo("LEGACY");
        assertThat(new Harness(database).capacity.status().get("accountingMatches")).isEqualTo(true);
        assertThat(database.queryForObject("SELECT subscription_rows+target_owners+preview_identities FROM news_control_capacity",Long.class)).isEqualTo(3);
        assertThat(database.queryForObject("SELECT daily_runs FROM news_control_capacity",Long.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT delivery_captured FROM news_daily_run",Boolean.class)).isTrue();
        Harness h=new Harness(database);DailyRun run=h.scheduling.runs(55,1).getFirst();
        String key="news-run:"+run.id(),hash=ContentIdentity.hash(NOW+"|UTC");
        RegionSnapshot region=h.region.bind(55,key,hash,NOW,NOW,RegionSnapshot::unresolved);
        var grant=new ExecutionGrant(UUID.randomUUID(),run.id().toString(),"news:"+run.id(),"node",1,NOW.plusSeconds(60));
        assertThat(h.scheduling.publish(run.id(),grant,()->grant,()->h.news.saveEdition(edition(55,region),key,hash).id(),()->NOW).status()).isEqualTo("READY");
        assertThat(h.capacity.status().get("accountingMatches")).isEqualTo(true);
    }
    private static SubscriptionChange change(long revision,boolean enabled){return new SubscriptionChange(revision,enabled,"UTC","00:00");}
    private static Target target(){return new Target(UUID.randomUUID(),Channel.EMAIL,RenderMode.FULL,true);}
    private static Edition edition(long owner,RegionSnapshot region){return new DigestEngine().build(UUID.randomUUID(),owner,NOW,NOW,ZoneId.of("UTC"),region,List.of());}
    private static void rejected(Runnable operation){assertCode(operation,ErrorCode.NEWS_029);}
    private static void assertCode(Runnable operation,ErrorCode code){assertThatThrownBy(operation::run).isInstanceOfSatisfying(NewsException.class,error->assertThat(error.code()).isEqualTo(code));}
    private static class Harness {
        final ControlCapacityRepository capacity;final NewsRepository news;final RegionRepository region;final DispatchRepository dispatch;final SchedulingRepository scheduling;
        Harness(JdbcTemplate jdbc) {this(jdbc,true);}
        Harness(JdbcTemplate jdbc,boolean enabled) {
            capacity=new ControlCapacityRepository(jdbc,new ControlCapacityProperties(1,1,1,1));var tx=Fixtures.transaction(jdbc);
            news=new NewsRepository(jdbc,JSON,tx,new GithubSnapshotRepository(jdbc,JSON,Fixtures.capacity(jdbc),tx),Fixtures.capacity(jdbc),capacity,Fixtures.registry(jdbc));
            region=new RegionRepository(jdbc,JSON,capacity,tx,Fixtures.registry(jdbc));
            var config=new DeliveryProperties(enabled,"http://localhost","fixture-token".repeat(4),"https://example.test/news");
            dispatch=new DispatchRepository(jdbc,tx.getTransactionManager(),JSON,config,new NewsDeliveryRenderer(JSON,config),capacity);
            scheduling=new SchedulingRepository(jdbc,tx.getTransactionManager(),dispatch,capacity);
        }
    }
}
