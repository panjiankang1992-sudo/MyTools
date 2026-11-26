package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.config.OutboundCapacityProperties;
import com.yuyutian.mytools.messaging.config.OutboundProperties;
import com.yuyutian.mytools.messaging.config.OutboundRetentionProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.provider.OutboundProvider;
import com.yuyutian.mytools.messaging.repository.OutboundCapacityRepository;
import com.yuyutian.mytools.messaging.repository.OutboundRepository;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:outbound-capacity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "messaging.outbound.enabled=true","messaging.outbound.relay-initial-delay-ms=3600000",
        "messaging.outbound.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "messaging.outbound.capacity.destination-rows=1","messaging.outbound.capacity.delivery-rows=1",
        "messaging.outbound.capacity.cancel-only-rows=1","messaging.outbound.capacity.payload-bytes=128",
        "messaging.outbound.retention.timer-enabled=false"})
class OutboundCapacityTest {
    @Autowired OutboundService service;
    @Autowired OutboundRepository repository;
    @Autowired OutboundCapacityRepository capacity;
    @Autowired OutboundProperties properties;
    @Autowired OutboundSecurity security;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Validator validator;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean OutboundSchedulerClient scheduler;
    @MockitoBean OutboundProvider provider;
    private final Binding binding=new Binding(77,"inbox",Channel.EMAIL,"Inbox","fixture@example.test","","fixture-verified",true);
    private UUID destination;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM messaging_outbound_attempt");jdbc.update("DELETE FROM messaging_outbound");jdbc.update("DELETE FROM messaging_destination");
        jdbc.update("UPDATE messaging_outbound_capacity SET destination_rows=0,delivery_rows=0,cancel_only_rows=0,payload_bytes=0 WHERE id=1");
        jdbc.update("UPDATE messaging_outbound_retention_state SET lease_token=NULL,lease_until_ms=NULL,last_status='NEVER_RUN'");
        properties.setEnabled(true);destination=service.bind(binding).id();
    }
    @Test void fullBindingBudgetAllowsReplayAndDisableButRejectsNewIdentity() {
        assertThat(service.bind(binding).id()).isEqualTo(destination);
        exhausted(()->service.bind(new Binding(78,"another",Channel.EMAIL,"Other","other@example.test","","verified",true)));
        assertThat(service.disable(77,destination).enabled()).isFalse();
        assertThat(service.bind(binding).enabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT destination_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(1);
        audit();
    }
    @Test void concurrentNewMessagesCannotOversubscribeLastSlot() throws Exception {
        List<Create> requests=new ArrayList<>();for(int i=0;i<8;i++)requests.add(request(null));
        var outcomes=concurrent(requests,false);assertThat(outcomes.stream().filter(Objects::nonNull).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delivery_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(1);audit();
    }
    @Test void concurrentReplaySharesOneReservationEvenWhenAlreadyFull() throws Exception {
        Create request=request(null);var outcomes=concurrent(Collections.nCopies(8,request),true);
        assertThat(outcomes.stream().map(View::id).distinct()).hasSize(1);audit();
    }
    @Test void separateCancellationReserveRemainsAvailableWhenNormalBudgetIsFull() {
        Create accepted=request(null);View delivery=service.create(accepted);
        Create cancelled=request(null);View tombstone=service.cancelByKey(cancelled);
        assertThat(tombstone.status()).isEqualTo("CANCELLED");assertThat(repository.get(tombstone.id(),false).orElseThrow().content()).isNull();
        assertThat(service.create(cancelled).id()).isEqualTo(tombstone.id());
        exhausted(()->service.create(request(null)));exhausted(()->service.cancelByKey(request(null)));
        assertThat(service.cancelByKey(cancelled).id()).isEqualTo(tombstone.id());
        assertThat(service.cancelByKey(accepted).id()).isEqualTo(delivery.id());
        assertThat(service.get(77,delivery.id()).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT capacity_bytes FROM messaging_outbound WHERE id=?",Long.class,tombstone.id().toString())).isZero();audit();
    }
    @Test void cancellationTombstoneRetainsStrictIdentityAndCannotBecomeSendable() {
        Create request=request(null);View tombstone=service.cancelByKey(request);
        Content changed=new Content(request.content().subject(),request.content().text(),"");
        assertThat(OutboundSecurity.hash(changed)).isEqualTo(request.payloadHash());
        assertThatThrownBy(()->service.create(new Create(77,request.idempotencyKey(),destination,changed,request.payloadHash(),request.expiresAt())))
                .isInstanceOf(OutboundException.class).extracting("code").isEqualTo(ErrorCode.OUTBOUND_CONFLICT);
        assertThatThrownBy(()->service.retry(77,tombstone.id(),new Retry(1,true))).isInstanceOf(OutboundException.class);
        assertThatThrownBy(()->service.get(78,tombstone.id())).isInstanceOf(OutboundException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE messaging_outbound SET status='ACCEPTED' WHERE id=?",tombstone.id().toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        properties.setEnabled(false);assertThat(service.cancelByKey(request).id()).isEqualTo(tombstone.id());assertThat(service.create(request).id()).isEqualTo(tombstone.id());
        verifyNoInteractions(provider,scheduler);audit();
    }
    @Test void acceptedDeliveryCanExecuteResolveAndRetryWithoutNewQuota() {
        View first=service.create(request(null));grant(first,1);
        when(provider.send(any(),any())).thenAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new OutboundProvider.Result("UNKNOWN",null,"MESSAGING_023");});
        Proof proof=new Proof(UUID.randomUUID(),UUID.randomUUID());
        assertThat(service.execute(first.id(),1,proof).status()).isEqualTo("UNKNOWN");
        assertThat(service.cancel(77,first.id()).status()).isEqualTo("UNKNOWN");
        assertThat(service.resolve(77,first.id(),new Resolution("NOT_SENT","provider-audit")).status()).isEqualTo("FAILED");
        View retry=service.retry(77,first.id(),new Retry(1,false));grant(retry,2);
        when(provider.send(any(),any())).thenReturn(new OutboundProvider.Result("PROVIDER_ACCEPTED",null,null));
        assertThat(service.execute(first.id(),2,proof).status()).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messaging_outbound_attempt",Long.class)).isEqualTo(2);audit();
    }
    @Test void attemptGenerationHasFixedUpperBound() {
        View first=service.create(request(null));jdbc.update("UPDATE messaging_outbound SET generation=99,status='FAILED' WHERE id=?",first.id().toString());
        View last=service.retry(77,first.id(),new Retry(99,false));assertThat(last.generation()).isEqualTo(100);grant(last,100);
        when(provider.send(any(),any())).thenReturn(new OutboundProvider.Result("FAILED",null,"MESSAGING_022"));
        service.execute(last.id(),100,new Proof(UUID.randomUUID(),UUID.randomUUID()));
        assertThatThrownBy(()->service.retry(77,last.id(),new Retry(100,true))).isInstanceOf(OutboundException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE messaging_outbound SET generation=101 WHERE id=?",last.id().toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);audit();
    }
    @Test void payloadAdmissionCountsUtf8SerializedBytesAndRollsBackOnFailure() {
        Content large=new Content("Subject","\u65b0".repeat(40),null);
        Create rejected=new Create(77,"too-big",destination,large,OutboundSecurity.hash(large),Instant.now().plusSeconds(3600));
        exhausted(()->service.create(rejected));assertThat(repository.byKey(77,"too-big")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT delivery_rows+payload_bytes FROM messaging_outbound_capacity",Long.class)).isZero();
        service.create(request(null));audit();
    }
    @Test void outerTransactionFailureReturnsAllReservations() {
        Create request=request(null);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{repository.lockCapacity();repository.insert(UUID.randomUUID(),request,Channel.EMAIL,Instant.now());throw new IllegalStateException("fixture-rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(repository.byKey(77,request.idempotencyKey())).isEmpty();assertThat(jdbc.queryForObject("SELECT delivery_rows+payload_bytes FROM messaging_outbound_capacity",Long.class)).isZero();audit();
    }
    @Test void cleanupReturnsBodySpaceWhileIdentityRowsRemainReserved() {
        OutboundCapacityRepository room=new OutboundCapacityRepository(jdbc,new OutboundCapacityProperties(1,3,1,128));
        OutboundRepository records=new OutboundRepository(jdbc,json,room);
        OutboundService client=new OutboundService(records,tx,properties,security,scheduler,provider,validator);
        View first=client.create(request(null));
        Content larger=new Content("Subject","x".repeat(65),null);
        Create next=new Create(77,"next",destination,larger,OutboundSecurity.hash(larger),Instant.now().plusSeconds(3600));
        exhausted(()->client.create(next));
        Instant future=Instant.now().plus(Duration.ofDays(4));
        jdbc.update("UPDATE messaging_outbound SET status='FAILED' WHERE id=?",first.id().toString());
        var retention=new OutboundRetentionService(jdbc,json,manager,new OutboundRetentionProperties(true,1,1,10),room);
        assertThat(retention.sweep(future).get("bodies")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delivery_rows FROM messaging_outbound_capacity",Long.class)).isEqualTo(1);
        assertThat(client.create(next).status()).isEqualTo("ACCEPTED");assertThat(client.get(77,first.id()).status()).isEqualTo("FAILED");audit();
    }
    @Test void unknownAndSendingKeepBodyQuotaAfterCleanup() {
        View first=service.create(request(null));jdbc.update("UPDATE messaging_outbound SET status='UNKNOWN' WHERE id=?",first.id().toString());
        long bytes=jdbc.queryForObject("SELECT payload_bytes FROM messaging_outbound_capacity",Long.class);
        var retention=new OutboundRetentionService(jdbc,json,manager,new OutboundRetentionProperties(true,1,1,10),capacity);
        assertThat(retention.sweep(Instant.now().plus(Duration.ofDays(4))).get("bodies")).isZero();
        assertThat(jdbc.queryForObject("SELECT payload_bytes FROM messaging_outbound_capacity",Long.class)).isEqualTo(bytes);audit();
    }
    @Test void invalidAccountingAbortsCleanupWithoutHidingDrift() {
        View first=service.create(request(null));jdbc.update("UPDATE messaging_outbound SET status='FAILED' WHERE id=?",first.id().toString());
        jdbc.update("UPDATE messaging_outbound_capacity SET payload_bytes=0");
        var retention=new OutboundRetentionService(jdbc,json,manager,new OutboundRetentionProperties(true,1,1,10),capacity);
        assertThat(capacity.status()).containsEntry("accountingMatches",false);
        assertThatThrownBy(()->retention.sweep(Instant.now().plus(Duration.ofDays(4)))).isInstanceOf(OutboundException.class);
        assertThat(repository.get(first.id(),false).orElseThrow().content()).isNotNull();assertThat(jdbc.queryForObject("SELECT payload_bytes FROM messaging_outbound_capacity",Long.class)).isZero();
    }
    private Create request(String html){Content content=new Content("Subject","Frozen body",html);return new Create(77,UUID.randomUUID().toString(),destination,content,OutboundSecurity.hash(content),Instant.now().plusSeconds(3600));}
    private void grant(View view,int generation){when(scheduler.authorize(any())).thenReturn(new Grant(UUID.randomUUID(),view.id().toString(),"message-outbound:"+view.id()+":"+generation,1,Instant.now().plusSeconds(120)));}
    private void audit(){assertThat(capacity.status()).containsEntry("accountingMatches",true);}
    private void exhausted(Runnable action){assertThatThrownBy(action::run).isInstanceOf(OutboundException.class).extracting("code").isEqualTo(ErrorCode.OUTBOUND_CAPACITY_EXCEEDED);}
    private List<View> concurrent(List<Create> requests,boolean replay) throws Exception {
        CountDownLatch start=new CountDownLatch(1);List<Future<View>> futures=new ArrayList<>();
        try(ExecutorService pool=Executors.newFixedThreadPool(8)) {
            for(Create request:requests)futures.add(pool.submit(()->{assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();try{return service.create(request);}catch(OutboundException e){if(replay)throw e;assertThat(e.code()).isEqualTo(ErrorCode.OUTBOUND_CAPACITY_EXCEEDED);return null;}}));
            start.countDown();List<View> outcomes=new ArrayList<>();for(var future:futures)outcomes.add(future.get(10,TimeUnit.SECONDS));return outcomes;
        }
    }
}
