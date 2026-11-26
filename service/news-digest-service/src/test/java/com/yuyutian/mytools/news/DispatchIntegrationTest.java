package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.mapper.DispatchRepository;
import com.yuyutian.mytools.news.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:news-dispatch;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "news.internal-token=test-proxy","news.admin-token=test-admin","news.region.node-id=executor-primary",
        "news.scheduling.enabled=true","news.scheduling.timer-enabled=false","news.scheduling.business-token=test-scheduler-token-at-least-thirty-two-characters",
        "news.scheduling.worker-token=test-job-token-at-least-thirty-two-characters","news.delivery.enabled=true","news.delivery.timer-enabled=false",
        "news.delivery.messaging-token=test-messaging-token-at-least-thirty-two-characters","news.delivery.reader-url=https://example.test/news"})
class DispatchIntegrationTest {
    private static final Instant NOW=Fixtures.CUTOFF;
    @Autowired NewsDispatchService service;
    @Autowired SchedulingService scheduling;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoSpyBean DispatchRepository repository;
    @MockitoBean NewsMessagingClient messaging;
    @MockitoBean NewsSchedulerClient scheduler;
    private final UUID mail=UUID.randomUUID(),feishu=UUID.randomUUID();
    @BeforeEach void setup() {
        for(String table:List.of("news_dispatch_outbox","news_delivery_batch","news_subscription_target","news_delivery_settings","news_task_outbox","news_daily_run","news_subscription","news_region_snapshot","news_edition"))jdbc.update("DELETE FROM "+table);
        Fixtures.resetControl(jdbc);
        Fixtures.resetRegistry(jdbc);
        clock.now.set(NOW);reset(messaging,scheduler);
        when(messaging.destinations(55)).thenReturn(List.of(new Destination(mail,Channel.EMAIL,"Inbox",true,NOW),new Destination(feishu,Channel.FEISHU,"Group",true,NOW)));
        when(messaging.submit(any(),any(),anyBoolean())).thenAnswer(i->receipt(i.getArgument(0),i.getArgument(1),i.getArgument(2)?"CANCELLED":"ACCEPTED",1));
        when(messaging.get(any(),any())).thenAnswer(i->receipt(i.getArgument(0),i.getArgument(1),"PROVIDER_ACCEPTED",1));
    }
    @Test void targetsValidateOwnerRevisionAndUnsupportedChannels() {
        assertThatThrownBy(()->service.changeTargets(56,change(0,mail,RenderMode.FULL))).isInstanceOf(NewsException.class);
        assertThat(service.changeTargets(55,change(0,mail,RenderMode.FULL)).revision()).isEqualTo(1);
        assertThatThrownBy(()->service.changeTargets(55,change(0,mail,RenderMode.FULL))).isInstanceOf(NewsException.class);
        assertThat(service.targets(56).targets()).isEmpty();
        assertThatThrownBy(()->service.changeTargets(55,new TargetChange(1,List.of(new Selection(mail,RenderMode.FULL,true),new Selection(mail,RenderMode.BRIEF,true))))).isInstanceOf(NewsException.class);
    }
    @Test void targetChangesApplyToNextRunAndPublicationFreezesPayload() {
        service.changeTargets(55,change(0,mail,RenderMode.FULL));DailyRun run=queued();
        service.changeTargets(55,new TargetChange(1,List.of(new Selection(mail,RenderMode.BRIEF,true),new Selection(feishu,RenderMode.FULL,true))));
        assertThat(service.history(55)).hasSize(1);assertThat(service.history(55).getFirst().mode()).isEqualTo(RenderMode.FULL);
        generate(run);
        BatchView view=service.history(55).getFirst();assertThat(view.editionId()).isNotNull();assertThat(view.parts()).isNotEmpty();
        String content=jdbc.queryForObject("SELECT content_json FROM news_dispatch_outbox",String.class);
        generate(run);assertThat(jdbc.queryForObject("SELECT content_json FROM news_dispatch_outbox",String.class)).isEqualTo(content);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_dispatch_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void failedOutboxPublicationRollsBackEditionAndReadyState() {
        service.changeTargets(55,change(0,mail,RenderMode.FULL));DailyRun run=queued();
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("simulated commit failure");}).when(repository).publish(any(),any(),any());
        assertThatThrownBy(()->generate(run)).isInstanceOf(IllegalStateException.class);
        assertThat(scheduling.run(55,run.id()).editionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_edition",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_dispatch_outbox",Integer.class)).isZero();
    }
    @Test void lostCreateReplyThenCancellationUsesOnlyCancelTombstone() {
        BatchView batch=published();
        doThrow(new NewsMessagingClient.Failure(false)).when(messaging).submit(any(),any(),eq(false));
        assertThat(service.relay()).isEqualTo(1);
        service.cancel(55,batch.id());clock.now.set(NOW.plusSeconds(60));
        service.relay();
        assertThat(service.get(55,batch.id()).status()).isEqualTo("CANCELLED");
        verify(messaging,times(1)).submit(any(),any(),eq(false));verify(messaging,times(1)).submit(any(),any(),eq(true));
    }
    @Test void foreignOwnerCannotReadCancelOrRetryAnotherBatch() {
        BatchView batch=published();UUID part=batch.parts().getFirst().id();
        assertThatThrownBy(()->service.get(56,batch.id())).isInstanceOf(NewsException.class);
        assertThatThrownBy(()->service.cancel(56,batch.id())).isInstanceOf(NewsException.class);
        assertThatThrownBy(()->service.retry(56,batch.id(),part,new Retry(1,true))).isInstanceOf(NewsException.class);
        assertThat(service.history(56)).isEmpty();
    }
    @Test void concurrentRelaysSubmitOnlyOneRequest() throws Exception {
        BatchView batch=published();
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> results=new ArrayList<>();for(int i=0;i<4;i++)results.add(pool.submit(service::relay));
            for(var result:results)result.get();
        }
        verify(messaging,times(1)).submit(any(),any(),eq(false));
        assertThat(service.get(55,batch.id()).status()).isEqualTo("ACCEPTED");
    }
    @Test void unknownRequiresRiskAndRetryReplayDoesNotResendAcceptedPart() {
        BatchView batch=published();
        doAnswer(i->receipt(i.getArgument(0),i.getArgument(1),"UNKNOWN",1)).when(messaging).submit(any(),any(),eq(false));
        service.relay();UUID part=batch.parts().getFirst().id();
        assertThatThrownBy(()->service.retry(55,batch.id(),part,new Retry(1,false))).isInstanceOf(NewsException.class);
        service.retry(55,batch.id(),part,new Retry(1,true));
        when(messaging.retry(any(),any())).thenAnswer(i->receipt(i.getArgument(0),i.getArgument(1),"PROVIDER_ACCEPTED",2));
        service.relay();service.retry(55,batch.id(),part,new Retry(1,true));service.relay();
        assertThat(service.get(55,batch.id()).status()).isEqualTo("PROVIDER_ACCEPTED");
        verify(messaging,times(1)).retry(any(),any());
    }
    @Test void subscriptionDisableAndTargetRemovalStopFrozenBatches() {
        BatchView batch=published();
        Subscription sub=scheduling.subscription(55).orElseThrow();
        scheduling.change(55,new SubscriptionChange(sub.revision(),false,"Asia/Shanghai","08:00"));
        service.relay();assertThat(service.get(55,batch.id()).status()).isEqualTo("CANCELLED");
        verify(messaging,never()).submit(any(),any(),eq(false));
    }
    @Test void staleClaimCannotOverwriteANewerProviderResult() {
        BatchView batch=published();Claim first=repository.claim(NOW).orElseThrow();
        Claim newer=repository.claim(NOW.plusSeconds(61)).orElseThrow();
        Dispatch part=repository.part(first.id(),false);Batch frozen=repository.batch(batch.id(),false);
        repository.reconcile(newer,receipt(frozen,part,"PROVIDER_ACCEPTED",1),NOW.plusSeconds(61));
        assertThatThrownBy(()->repository.reconcile(first,receipt(frozen,part,"FAILED",1),NOW.plusSeconds(62))).isInstanceOf(NewsException.class);
        assertThat(service.get(55,batch.id()).status()).isEqualTo("PROVIDER_ACCEPTED");
    }
    @Test void permanentRejectionNeedsExplicitOriginalKeyRedrive() {
        BatchView batch=published();
        doThrow(new NewsMessagingClient.Failure(true)).when(messaging).submit(any(),any(),eq(false));
        service.relay();assertThat(service.get(55,batch.id()).status()).isEqualTo("REJECTED");
        service.redrive(55,batch.id(),batch.parts().getFirst().id());
        doAnswer(i->receipt(i.getArgument(0),i.getArgument(1),"ACCEPTED",1)).when(messaging).submit(any(),any(),eq(false));
        service.relay();
        var captor=org.mockito.ArgumentCaptor.forClass(Dispatch.class);verify(messaging,times(2)).submit(any(),captor.capture(),eq(false));
        assertThat(captor.getAllValues().stream().map(Dispatch::id).distinct()).hasSize(1);
    }
    @Test void oneChannelFailureNeverResendsTheOtherSuccessfulChannel() {
        service.changeTargets(55,new TargetChange(0,List.of(new Selection(mail,RenderMode.FULL,true),new Selection(feishu,RenderMode.FULL,true))));
        generate(queued());
        doAnswer(i->{Batch batch=i.getArgument(0);return receipt(batch,i.getArgument(1),batch.channel()==Channel.EMAIL?"UNKNOWN":"PROVIDER_ACCEPTED",1);}).when(messaging).submit(any(),any(),eq(false));
        assertThat(service.relay()).isEqualTo(2);
        service.relay();
        assertThat(service.history(55)).extracting(BatchView::status).containsExactlyInAnyOrder("UNKNOWN","PROVIDER_ACCEPTED");
        verify(messaging,times(2)).submit(any(),any(),eq(false));
    }
    @Test void laterPartsWaitForEarlierAcceptanceButCanStillBeCancelled() {
        BatchView batch=published();String first=batch.parts().getFirst().id().toString();
        jdbc.update("UPDATE news_dispatch_outbox SET part_count=2 WHERE id=?",first);
        jdbc.update("INSERT INTO news_dispatch_outbox(id,batch_id,part_index,part_count,content_json,payload_hash,status,available_ms,updated_ms) SELECT ?,batch_id,2,2,content_json,payload_hash,'PENDING',available_ms,updated_ms FROM news_dispatch_outbox WHERE id=?",UUID.randomUUID().toString(),first);
        doAnswer(i->receipt(i.getArgument(0),i.getArgument(1),"UNKNOWN",1)).when(messaging).submit(any(),any(),eq(false));
        assertThat(service.relay()).isEqualTo(1);
        assertThat(service.get(55,batch.id()).parts()).extracting(PartView::status).containsExactly("UNKNOWN","PENDING");
        service.cancel(55,batch.id());assertThat(service.relay()).isEqualTo(2);
        assertThat(service.get(55,batch.id()).status()).isEqualTo("CANCELLED");
        verify(messaging,times(1)).submit(any(),any(),eq(false));
    }
    @Test void cancellationClearsUnsubmittedRetryIntentEvenIfProviderAlreadyFailed() {
        BatchView batch=published();
        doAnswer(i->receipt(i.getArgument(0),i.getArgument(1),"FAILED",1)).when(messaging).submit(any(),any(),anyBoolean());
        service.relay();
        UUID part=batch.parts().getFirst().id();service.retry(55,batch.id(),part,new Retry(1,false));
        service.cancel(55,batch.id());assertThat(service.relay()).isEqualTo(1);
        assertThat(service.get(55,batch.id()).parts().getFirst().retryPending()).isFalse();
        clock.now.set(NOW.plusSeconds(600));assertThat(service.relay()).isZero();
        verify(messaging,never()).retry(any(),any());
    }
    private TargetChange change(long revision,UUID destination,RenderMode mode){return new TargetChange(revision,List.of(new Selection(destination,mode,true)));}
    private DailyRun queued(){scheduling.change(55,new SubscriptionChange(0,true,"Asia/Shanghai","08:00"));scheduling.scan();return scheduling.runs(55,30).getFirst();}
    private void generate(DailyRun run){UUID task=run.taskId()==null?UUID.nameUUIDFromBytes(run.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)):run.taskId();when(scheduler.authorize(any())).thenReturn(new ExecutionGrant(task,run.id().toString(),"news:"+run.id(),"executor-primary",1,clock.instant().plusSeconds(600)));scheduling.generate(run.id(),new ExecutionProof(UUID.randomUUID(),UUID.randomUUID()));}
    private BatchView published(){service.changeTargets(55,change(0,mail,RenderMode.FULL));DailyRun run=queued();generate(run);return service.history(55).getFirst();}
    private Receipt receipt(Batch b,Dispatch p,String state,int generation){return new Receipt(p.id(),b.destinationId(),b.channel(),p.payloadHash(),state,generation,null,b.expiresAt(),clock.instant());}
    @TestConfiguration static class Config {
        @Bean @Primary MutableClock clock(){return new MutableClock();}
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        /** 返回协调世界时。 */ @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        /** 保留测试统一时区。 */ @Override public Clock withZone(ZoneId zone){return this;}
        /** 返回可控测试时间。 */ @Override public Instant instant(){return now.get();}
    }
}
