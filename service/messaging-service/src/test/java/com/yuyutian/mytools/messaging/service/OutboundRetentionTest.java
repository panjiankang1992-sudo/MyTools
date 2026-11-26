package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.OutboundRetentionProperties;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.repository.OutboundRepository;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import com.yuyutian.mytools.messaging.provider.OutboundProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:outbound-retention;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "messaging.outbound.enabled=true","messaging.outbound.relay-initial-delay-ms=3600000",
        "messaging.outbound.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "messaging.outbound.retention.enabled=true","messaging.outbound.retention.timer-enabled=false","messaging.outbound.retention.batch-size=2"})
class OutboundRetentionTest {
    @Autowired OutboundRetentionService retention;
    @Autowired OutboundService service;
    @Autowired OutboundRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.yuyutian.mytools.messaging.repository.OutboundCapacityRepository capacity;
    @MockitoBean OutboundSchedulerClient scheduler;
    @MockitoBean OutboundProvider provider;
    private final Instant now=Instant.now();
    private final Instant old=now.minus(Duration.ofDays(100));
    private UUID destination;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM messaging_outbound_attempt");jdbc.update("DELETE FROM messaging_outbound");jdbc.update("DELETE FROM messaging_destination");
        jdbc.update("UPDATE messaging_outbound_capacity SET destination_rows=0,delivery_rows=0,cancel_only_rows=0,payload_bytes=0 WHERE id=1");
        jdbc.update("UPDATE messaging_outbound_retention_state SET lease_token=NULL,lease_until_ms=NULL,last_status='NEVER_RUN',counts_json=NULL");
        destination=service.bind(new Binding(77,UUID.randomUUID().toString(),Channel.EMAIL,"Inbox","verified@example.test","","test-only-verification",true)).id();
    }
    @Test void compactedReplayPreservesExactIdentityIncludingNullVersusEmptyHtml() {
        Create request=request(null);View original=record(request,"PROVIDER_ACCEPTED");
        assertThat(retention.sweep(now).get("bodies")).isEqualTo(1);
        Delivery compacted=repository.get(original.id(),false).orElseThrow();
        assertThat(compacted.content()).isNull();assertThat(compacted.contentIdentityHash()).isEqualTo(OutboundSecurity.identityHash(request.content()));
        assertThat(service.create(request).id()).isEqualTo(original.id());assertThat(service.cancelByKey(request).status()).isEqualTo("PROVIDER_ACCEPTED");
        Content emptyHtml=new Content(request.content().subject(),request.content().text(),"");
        assertThat(OutboundSecurity.hash(emptyHtml)).isEqualTo(request.payloadHash());
        assertThatThrownBy(()->service.create(new Create(77,request.idempotencyKey(),destination,emptyHtml,request.payloadHash(),request.expiresAt()))).isInstanceOf(OutboundException.class);
        assertThatThrownBy(()->service.get(78,original.id())).isInstanceOf(OutboundException.class);
        assertThatThrownBy(()->service.retry(77,original.id(),new Retry(1,true))).isInstanceOf(OutboundException.class);
        verifyNoInteractions(provider,scheduler);
    }
    @Test void onlyFinishedExpiredRecordsWithoutRelayLeaseAreCompacted() {
        for(String state:List.of("UNKNOWN","SENDING","ACCEPTED"))record(request(null),state);
        View leased=record(request(null),"FAILED");jdbc.update("UPDATE messaging_outbound SET relay_until_ms=? WHERE id=?",now.plusSeconds(60).toEpochMilli(),leased.id().toString());
        assertThat(retention.sweep(now).get("bodies")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messaging_outbound WHERE content_json IS NULL",Integer.class)).isZero();
    }
    @Test void attemptsExpireOnlyAfterStableDeliveryAndBodyCompaction() {
        View done=record(request(""),"FAILED"),unknown=record(request(null),"UNKNOWN");
        attempt(done.id(),"FAILED",null);attempt(unknown.id(),"UNKNOWN",null);
        assertThat(retention.sweep(now)).containsEntry("bodies",1).containsEntry("attempts",1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messaging_outbound_attempt WHERE delivery_id=?",Integer.class,unknown.id().toString())).isEqualTo(1);
        assertThat(repository.get(unknown.id(),false).orElseThrow().content()).isNotNull();
        assertThat(service.get(77,done.id()).status()).isEqualTo("FAILED");
    }
    @Test void explicitUnknownResolutionCanStillCompleteBeforeLaterCompaction() {
        View unknown=record(request(null),"UNKNOWN");String attempt=attempt(unknown.id(),"UNKNOWN",null);
        jdbc.update("UPDATE messaging_outbound SET attempt_id=? WHERE id=?",attempt,unknown.id().toString());
        retention.sweep(now);
        assertThat(service.resolve(77,unknown.id(),new Resolution("PROVIDER_ACCEPTED","provider-verified-reference")).status()).isEqualTo("PROVIDER_ACCEPTED");
        retention.sweep(now.plus(Duration.ofDays(91)));
        assertThat(service.get(77,unknown.id()).status()).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(repository.get(unknown.id(),false).orElseThrow().content()).isNull();
        verifyNoInteractions(provider,scheduler);
    }
    @Test void leaseAndBatchLimitsBoundRepeatedRuns() {
        for(int i=0;i<3;i++)record(request(null),"CANCELLED");
        jdbc.update("UPDATE messaging_outbound_retention_state SET lease_token='other',lease_until_ms=?",now.plusSeconds(60).toEpochMilli());
        assertThat(retention.sweep(now)).isEmpty();
        assertThat(retention.sweep(now.plusSeconds(61)).get("bodies")).isEqualTo(2);
        assertThat(retention.sweep(now.plusSeconds(62)).get("bodies")).isEqualTo(1);
        assertThat(retention.sweep(now.plusSeconds(63)).get("bodies")).isZero();
    }
    @Test void corruptedBodyRollsBackBatchAndRecordsSanitizedFailure() {
        record(request(null),"CANCELLED");View broken=record(request(null),"FAILED");
        jdbc.update("UPDATE messaging_outbound SET content_json='private-corrupt-data',updated_ms=? WHERE id=?",old.plusSeconds(1).toEpochMilli(),broken.id().toString());
        assertThatThrownBy(()->retention.sweep(now)).isInstanceOf(OutboundException.class).hasMessageNotContaining("private-corrupt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messaging_outbound WHERE content_json IS NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_status FROM messaging_outbound_retention_state",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM messaging_outbound_retention_state",String.class)).isNull();
    }
    @Test void disabledPolicyAllowsOnlyReadOnlyAggregateInspection() throws Exception {
        record(request(null),"UNKNOWN");
        OutboundRetentionService disabled=new OutboundRetentionService(jdbc,json,manager,new OutboundRetentionProperties(false,90,90,2),capacity);
        String status=json.writeValueAsString(disabled.status(now));
        assertThat(status).contains("unknown\":1").doesNotContain("verified@example","Frozen body");
        assertThatThrownBy(()->disabled.sweep(now)).isInstanceOf(OutboundException.class);
    }
    private Create request(String html){Content content=new Content("Subject","Frozen body",html);return new Create(77,UUID.randomUUID().toString(),destination,content,OutboundSecurity.hash(content),old);}
    private View record(Create request,String state){UUID id=UUID.randomUUID();new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{repository.lockCapacity();repository.insert(id,request,Channel.EMAIL,old);repository.state(id,state,old);});return service.get(77,id);}
    private String attempt(UUID delivery,String state,String resolution){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO messaging_outbound_attempt(id,delivery_id,generation,fencing_token,status,resolution,started_ms,finished_ms) VALUES(?,?,1,1,?,?,?,?)",id,delivery.toString(),state,resolution,old.minusSeconds(5).toEpochMilli(),old.toEpochMilli());return id;}
}
