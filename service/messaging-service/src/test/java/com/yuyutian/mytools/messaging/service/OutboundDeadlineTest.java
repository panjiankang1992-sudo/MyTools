package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.config.OutboundProperties;
import com.yuyutian.mytools.messaging.config.OutboundRetentionProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.provider.OutboundProvider;
import com.yuyutian.mytools.messaging.repository.OutboundCapacityRepository;
import com.yuyutian.mytools.messaging.repository.OutboundRepository;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:outbound-deadlines;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "messaging.outbound.enabled=true","messaging.outbound.relay-initial-delay-ms=3600000",
        "messaging.outbound.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "messaging.outbound.retention.enabled=false","messaging.outbound.retention.timer-enabled=false"})
class OutboundDeadlineTest {
    @Autowired OutboundService service;
    @Autowired OutboundRepository repository;
    @Autowired OutboundCapacityRepository capacity;
    @Autowired OutboundProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean OutboundSchedulerClient scheduler;
    @MockitoBean OutboundProvider provider;
    private UUID destination;
    @BeforeEach void setup(){
        jdbc.update("DELETE FROM messaging_outbound_attempt");jdbc.update("DELETE FROM messaging_outbound");jdbc.update("DELETE FROM messaging_destination");
        jdbc.update("UPDATE messaging_outbound_capacity SET destination_rows=0,delivery_rows=0,cancel_only_rows=0,payload_bytes=0 WHERE id=1");
        jdbc.update("UPDATE messaging_outbound_retention_state SET lease_token=NULL,lease_until_ms=NULL,last_status='NEVER_RUN'");
        jdbc.update("UPDATE messaging_outbound_deadline_state SET lease_token=NULL,lease_until_ms=NULL,last_status='NEVER_RUN'");
        properties.setEnabled(true);destination=service.bind(new Binding(77,"inbox",Channel.EMAIL,"Inbox","fixture@example.test","","verified",true)).id();
    }
    @Test void deadlineIsImmutableAndLegacyResponseOmitsAbsentField() throws Exception {
        Create request=request(Instant.now().plusSeconds(600));View first=service.create(request);
        assertThat(json.readTree(json.writeValueAsBytes(first)).has("contentExpiresAt")).isTrue();
        for(Instant deadline:Arrays.asList(null,request.contentExpiresAt().plusSeconds(1),request.contentExpiresAt().minusSeconds(1))) {
            rejected(()->service.create(copy(request,deadline)),ErrorCode.OUTBOUND_CONFLICT);
        }
        assertThat(service.create(request).id()).isEqualTo(first.id());
        View legacy=service.create(request(null));assertThat(json.readTree(json.writeValueAsBytes(legacy)).has("contentExpiresAt")).isFalse();
    }
    @Test void invalidNewDeadlinesDoNotConsumeCapacity(){
        for(Instant deadline:List.of(Instant.now().minusSeconds(1),Instant.now().plus(Duration.ofDays(731)),Instant.MAX)) {
            rejected(()->service.create(request(deadline)),ErrorCode.OUTBOUND_INVALID);
        }
        assertThat(jdbc.queryForObject("SELECT delivery_rows FROM messaging_outbound_capacity",Long.class)).isZero();audit();
    }
    @Test void bodylessCancellationBeforeCreatePreservesStrictIdentity(){
        Create request=request(Instant.now().plusSeconds(600));View cancelled=service.cancelByIdentity(identity(request));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");assertThat(record(cancelled).content()).isNull();
        assertThat(service.create(request).id()).isEqualTo(cancelled.id());
        Content emptyHtml=new Content(request.content().subject(),request.content().text(),"");
        rejected(()->service.create(new Create(77,request.idempotencyKey(),destination,emptyHtml,request.payloadHash(),request.expiresAt(),request.contentExpiresAt())),ErrorCode.OUTBOUND_CONFLICT);
        rejected(()->service.cancelByIdentity(identity(copy(request,null))),ErrorCode.OUTBOUND_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT cancel_only_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(1);audit();verifyNoInteractions(provider,scheduler);
    }
    @Test void cancellationValidatesOwnerAndHashWithoutRequiringLiveBodyOrEnabledSending(){
        Create request=request(Instant.now().minusSeconds(60));CancelIdentity original=identity(request);
        rejected(()->service.cancelByIdentity(new CancelIdentity(78,original.idempotencyKey(),destination,original.payloadHash(),original.contentIdentityHash(),original.expiresAt(),original.contentExpiresAt())),ErrorCode.DESTINATION_NOT_FOUND);
        rejected(()->service.cancelByIdentity(new CancelIdentity(77,original.idempotencyKey(),destination,original.payloadHash(),"0".repeat(64),original.expiresAt(),original.contentExpiresAt())),ErrorCode.OUTBOUND_INVALID);
        properties.setEnabled(false);View cancelled=service.cancelByIdentity(original);
        assertThat(service.create(request).id()).isEqualTo(cancelled.id());verifyNoInteractions(provider,scheduler);audit();
    }
    @Test void concurrentCreateAndBodylessCancelConvergeOnOneCancelledIdentity() throws Exception {
        Create request=request(Instant.now().plusSeconds(600));CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)){
            Future<View> create=pool.submit(()->{start.await();return service.create(request);});
            Future<View> cancel=pool.submit(()->{start.await();return service.cancelByIdentity(identity(request));});start.countDown();
            assertThat(create.get(10,TimeUnit.SECONDS).id()).isEqualTo(cancel.get(10,TimeUnit.SECONDS).id());
        }
        assertThat(repository.byKey(77,request.idempotencyKey()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT delivery_rows+cancel_only_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(1);audit();
    }
    @Test void executionStopsAtDeadlineWithoutWaitingForCleanup(){
        View view=seed(request(Instant.now().minusSeconds(60)));grant(view);
        View stopped=service.execute(view.id(),1,new Proof(UUID.randomUUID(),UUID.randomUUID()));
        assertThat(stopped.status()).isEqualTo("EXPIRED");assertThat(stopped.errorCode()).isEqualTo("MESSAGING_028");
        assertThat(record(view).content()).isNotNull();verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messaging_outbound_attempt",Long.class)).isZero();
    }
    @Test void retryStopsAtDeadlineForKnownFailureAndUnknown(){
        for(String status:List.of("FAILED","UNKNOWN")){
            View view=seed(request(Instant.now().minusSeconds(60)));jdbc.update("UPDATE messaging_outbound SET status=? WHERE id=?",status,view.id().toString());
            rejected(()->service.retry(77,view.id(),new Retry(1,true)),ErrorCode.OUTBOUND_CONTENT_EXPIRED);
            assertThat(record(view).generation()).isEqualTo(1);
        }
        verifyNoInteractions(provider,scheduler);
    }
    @Test void hardCleanupPurgesUnknownBodyButKeepsAttemptAndManualReconciliation(){
        Create request=request(Instant.now().minusSeconds(60));View view=seed(request);UUID attempt=begin(view);
        tx.executeWithoutResult(s->repository.finish(view.id(),attempt,"UNKNOWN",null,"MESSAGING_023",Instant.now()));
        properties.setEnabled(false);assertThat(retention(10).sweepDeadlines(Instant.now())).containsEntry("deadlineBodies",1);
        assertThat(record(view).content()).isNull();assertThat(record(view).status()).isEqualTo("UNKNOWN");
        assertThat(service.cancelByIdentity(identity(request)).status()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messaging_outbound_attempt",Long.class)).isEqualTo(1);
        assertThat(service.resolve(77,view.id(),new Resolution("NOT_SENT","manual-fixture-audit")).status()).isEqualTo("FAILED");
        assertThat(service.create(request).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM messaging_outbound_capacity",Long.class)).isZero();audit();
    }
    @Test void inFlightOutcomeSurvivesConcurrentHardCleanup(){
        Create request=request(Instant.now().plusSeconds(600));View view=service.create(request);grant(view);
        when(provider.send(any(),any())).thenAnswer(call->{
            assertThat(retention(10).sweepDeadlines(request.contentExpiresAt().plusSeconds(1))).containsEntry("deadlineBodies",1);
            assertThat(record(view).status()).isEqualTo("SENDING");assertThat(record(view).content()).isNull();
            return new OutboundProvider.Result("PROVIDER_ACCEPTED","fixture-accepted",null);
        });
        assertThat(service.execute(view.id(),1,new Proof(UUID.randomUUID(),UUID.randomUUID())).status()).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT status FROM messaging_outbound_attempt",String.class)).isEqualTo("PROVIDER_ACCEPTED");audit();
    }
    @Test void cleanupDoesNotRestoreLateWriteAfterManualResolution(){
        View view=seed(request(Instant.now().minusSeconds(60)));UUID attempt=begin(view);
        tx.executeWithoutResult(s->repository.finish(view.id(),attempt,"UNKNOWN",null,"MESSAGING_023",Instant.now()));
        retention(10).sweepDeadlines(Instant.now());service.resolve(77,view.id(),new Resolution("NOT_SENT","manual-fixture-audit"));
        tx.executeWithoutResult(s->repository.finish(view.id(),attempt,"PROVIDER_ACCEPTED",null,null,Instant.now()));
        assertThat(record(view).status()).isEqualTo("FAILED");assertThat(jdbc.queryForObject("SELECT resolution FROM messaging_outbound_attempt",String.class)).isEqualTo("manual-fixture-audit");
    }
    @Test void deadlineBatchIsBoundedAndLegacyUnknownBodyRemains(){
        View legacy=seed(request(null));jdbc.update("UPDATE messaging_outbound SET status='UNKNOWN' WHERE id=?",legacy.id().toString());
        seed(request(Instant.now().minusSeconds(60)));seed(request(Instant.now().minusSeconds(60)));
        assertThat(retention(1).sweepDeadlines(Instant.now())).containsEntry("deadlineBodies",1);
        assertThat(retention(1).sweepDeadlines(Instant.now())).containsEntry("deadlineBodies",1);
        assertThat(retention(1).sweepDeadlines(Instant.now())).containsEntry("deadlineBodies",0);
        assertThat(record(legacy).content()).isNotNull();audit();
    }
    @Test void deadlineLeaseAndCheckpointAreIndependentOfOrdinaryRetention(){
        seed(request(Instant.now().minusSeconds(60)));jdbc.update("UPDATE messaging_outbound_retention_state SET last_status='FAILED'");
        jdbc.update("UPDATE messaging_outbound_deadline_state SET lease_token=?,lease_until_ms=?",UUID.randomUUID().toString(),Instant.now().plusSeconds(120).toEpochMilli());
        assertThat(retention(10).sweepDeadlines(Instant.now())).isEmpty();
        jdbc.update("UPDATE messaging_outbound_deadline_state SET lease_token=NULL,lease_until_ms=NULL");
        assertThat(retention(10).sweepDeadlines(Instant.now())).containsEntry("deadlineBodies",1);
        assertThat(retention(10).sweepDeadlines(Instant.now())).containsEntry("deadlineBodies",0);
        assertThat(jdbc.queryForObject("SELECT last_status FROM messaging_outbound_retention_state",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_status FROM messaging_outbound_deadline_state",String.class)).isEqualTo("SUCCEEDED");
    }
    @Test void invalidAccountingRollsBackBodyRemovalAndRecordsFailure(){
        View view=seed(request(Instant.now().minusSeconds(60)));jdbc.update("UPDATE messaging_outbound_capacity SET payload_bytes=0");
        rejected(()->retention(10).sweepDeadlines(Instant.now()),ErrorCode.OUTBOUND_RETENTION_FAILED);
        assertThat(record(view).content()).isNotNull();assertThat(record(view).status()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT last_status FROM messaging_outbound_deadline_state",String.class)).isEqualTo("FAILED");
    }
    private OutboundRetentionService retention(int batch){return new OutboundRetentionService(jdbc,json,manager,new OutboundRetentionProperties(false,90,90,batch),capacity);}
    private Create request(Instant deadline){Content content=new Content("Subject","Frozen body",null);return new Create(77,UUID.randomUUID().toString(),destination,content,OutboundSecurity.hash(content),Instant.now().plusSeconds(3600),deadline);}
    private Create copy(Create r,Instant deadline){return new Create(r.ownerId(),r.idempotencyKey(),r.destinationId(),r.content(),r.payloadHash(),r.expiresAt(),deadline);}
    private CancelIdentity identity(Create r){return new CancelIdentity(r.ownerId(),r.idempotencyKey(),r.destinationId(),r.payloadHash(),OutboundSecurity.identityHash(r.content()),r.expiresAt(),r.contentExpiresAt());}
    private View seed(Create request){UUID id=UUID.randomUUID();tx.executeWithoutResult(s->{repository.lockCapacity();repository.insert(id,request,Channel.EMAIL,Instant.now().minusSeconds(120));});return service.get(77,id);}
    private Delivery record(View view){return repository.get(view.id(),false).orElseThrow();}
    private UUID begin(View view){return tx.execute(s->repository.begin(record(view),UUID.randomUUID(),1,Instant.now()));}
    private void grant(View view){when(scheduler.authorize(any())).thenReturn(new Grant(UUID.randomUUID(),view.id().toString(),"message-outbound:"+view.id()+":1",1,Instant.now().plusSeconds(120)));}
    private void rejected(Runnable action,ErrorCode code){assertThatThrownBy(action::run).isInstanceOf(OutboundException.class).extracting("code").isEqualTo(code);}
    private void audit(){assertThat(capacity.status()).containsEntry("accountingMatches",true);}
}
