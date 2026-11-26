package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.OutboundProperties;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.provider.OutboundProvider;
import com.yuyutian.mytools.messaging.repository.OutboundRepository;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"messaging.outbound.enabled=true", "messaging.outbound.relay-initial-delay-ms=3600000",
        "messaging.outbound.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class OutboundServiceTest {
    @Autowired OutboundService service;
    @Autowired OutboundRepository repository;
    @Autowired OutboundSecurity security;
    @Autowired OutboundProperties properties;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OutboundSchedulerClient scheduler;
    @MockitoBean OutboundProvider provider;
    private static final long OWNER = 82001;
    private final Proof proof = new Proof(UUID.randomUUID(), UUID.randomUUID());
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM messaging_outbound_attempt");
        jdbc.update("DELETE FROM messaging_outbound");
        jdbc.update("DELETE FROM messaging_destination");
        jdbc.update("UPDATE messaging_outbound_capacity SET destination_rows=0,delivery_rows=0,cancel_only_rows=0,payload_bytes=0 WHERE id=1");
        properties.setEnabled(true);
        when(scheduler.create(any())).thenReturn(UUID.randomUUID());
        when(provider.send(any(), any())).thenReturn(new OutboundProvider.Result("PROVIDER_ACCEPTED", "receipt", null));
    }
    @Test void bindingIsIdempotentEncryptedAndOwnerScoped() {
        Binding binding = binding(Channel.EMAIL, "person@example.com");
        Destination d = service.bind(binding);
        assertThat(service.bind(binding).id()).isEqualTo(d.id());
        assertThat(service.destinations(OWNER + 1)).isEmpty();
        assertThat(service.destinations(OWNER)).hasSize(1);
        assertThat(repository.secret(OWNER, d.id())).doesNotContain("person", "example.com");
        assertThatThrownBy(() -> security.decrypt(repository.secret(OWNER, d.id()), "other-owner"))
                .isInstanceOf(OutboundException.class);
        assertThatThrownBy(() -> service.bind(new Binding(OWNER, binding.bindingKey(), Channel.EMAIL,
                "Inbox", "other@example.com", "", "verified-manually", true))).isInstanceOf(OutboundException.class);
        assertThatThrownBy(() -> service.disable(OWNER+1,d.id())).isInstanceOf(OutboundException.class);
    }
    @Test void rejectsUnverifiedUnsafeAndUnsupportedDestinations() {
        assertThatThrownBy(() -> service.bind(new Binding(OWNER, "unverified", Channel.EMAIL, "Inbox", "person@example.com", "", "reference", false))).isInstanceOf(OutboundException.class);
        for (String address : new String[]{"https://localhost/hook/123", "https://open.feishu.cn.evil.test/open-apis/bot/v2/hook/1234567890123456", "https://open.feishu.cn/open-apis/bot/v2/hook/1234567890123456?evil=1"}) {
            assertThatThrownBy(() -> service.bind(binding(Channel.FEISHU, address))).isInstanceOf(OutboundException.class);
        }
        assertThatThrownBy(() -> service.bind(binding(Channel.EMAIL, "one@example.com,two@example.com"))).isInstanceOf(OutboundException.class);
        assertThatThrownBy(() -> service.bind(binding(Channel.QQ, "openid"))).isInstanceOf(OutboundException.class).hasMessageContaining("unavailable");
    }
    @Test void createRetriesDoNotResubmitOrMutateFrozenContent() {
        Create request = request();
        View first = service.create(request);
        assertThat(service.create(request).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> service.get(OWNER+1,first.id())).isInstanceOf(OutboundException.class);
        Content changed = new Content("Subject", "changed", null);
        assertThatThrownBy(() -> service.create(new Create(OWNER, request.idempotencyKey(), request.destinationId(), changed, OutboundSecurity.hash(changed), request.expiresAt())))
                .isInstanceOf(OutboundException.class);
        verifyNoInteractions(scheduler);
        properties.setEnabled(false);
        assertThat(service.create(request).id()).isEqualTo(first.id());
    }
    @Test void schedulerLostResponseReusesTheSameIdentity() {
        View d = service.create(request());
        when(scheduler.create(any())).thenThrow(new IllegalStateException("lost reply")).thenReturn(UUID.randomUUID());
        service.relay();
        assertThat(service.get(OWNER,d.id()).status()).isEqualTo("ACCEPTED");
        jdbc.update("UPDATE messaging_outbound SET next_attempt_ms=0");
        service.relay();
        var captor = org.mockito.ArgumentCaptor.forClass(Delivery.class);
        verify(scheduler,times(2)).create(captor.capture());
        assertThat(captor.getAllValues().stream().map(OutboundSchedulerClient::key).distinct()).hasSize(1);
        verifyNoInteractions(provider);
    }
    @Test void replayAfterAcceptanceCannotSendAgain() {
        View d = service.create(request()); grant(d, 1);
        assertThat(service.execute(d.id(),1,proof).status()).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(repository.get(d.id(),false).orElseThrow().taskId()).isNotNull();
        assertThat(service.execute(d.id(),1,proof).status()).isEqualTo("PROVIDER_ACCEPTED");
        verify(provider,times(1)).send(any(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messaging_outbound_attempt", Integer.class)).isEqualTo(1);
    }
    @Test void concurrentExecutionsCannotBothCallProvider() throws Exception {
        View d = service.create(request()); grant(d, 1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(provider.send(any(),any())).thenAnswer(invocation -> { entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return new OutboundProvider.Result("PROVIDER_ACCEPTED",null,null); });
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<View> first = pool.submit(() -> service.execute(d.id(),1,proof));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThat(service.execute(d.id(),1,proof).status()).isEqualTo("SENDING"); }
            finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS).status()).isEqualTo("PROVIDER_ACCEPTED");
        }
        verify(provider,times(1)).send(any(),any());
    }
    @Test void cancellationAndDisabledDestinationPreventSending() {
        View first = service.create(request()); grant(first,1);
        assertThat(service.cancel(OWNER, first.id()).status()).isEqualTo("CANCELLED");
        assertThat(service.execute(first.id(),1,proof).status()).isEqualTo("CANCELLED");
        View second = service.create(request()); grant(second,1);
        service.disable(OWNER, second.destinationId());
        assertThat(service.execute(second.id(),1,proof).status()).isEqualTo("CANCELLED");
        verifyNoInteractions(provider);
    }
    @Test void cancelledInFlightKeepsTheRealProviderOutcome() {
        View d=service.create(request()); grant(d,1);
        when(provider.send(any(),any())).thenAnswer(invocation -> {
            assertThat(service.cancel(OWNER,d.id()).status()).isEqualTo("SENDING");
            return new OutboundProvider.Result("PROVIDER_ACCEPTED",null,null);
        });
        assertThat(service.execute(d.id(),1,proof).status()).isEqualTo("PROVIDER_ACCEPTED");
    }
    @Test void unknownResultRequiresExplicitRiskAndOldGenerationCannotRun() {
        View d=service.create(request()); grant(d,1);
        when(provider.send(any(),any())).thenThrow(new IllegalStateException("sensitive remote payload"));
        assertThat(service.execute(d.id(),1,proof).status()).isEqualTo("UNKNOWN");
        service.relay();
        assertThatThrownBy(() -> service.retry(OWNER,d.id(),new Retry(1,false))).isInstanceOf(OutboundException.class);
        assertThat(service.retry(OWNER,d.id(),new Retry(1,true)).generation()).isEqualTo(2);
        assertThatThrownBy(() -> service.execute(d.id(),1,proof)).isInstanceOf(OutboundException.class);
        verify(provider,times(1)).send(any(),any());
        assertThat(jdbc.queryForObject("SELECT resolution FROM messaging_outbound_attempt",String.class)).isEqualTo("DUPLICATE_RISK_ACCEPTED");
    }
    @Test void unknownReconciliationRejectsLateResults() {
        View d=service.create(request()); grant(d,1);
        when(provider.send(any(),any())).thenReturn(new OutboundProvider.Result("UNKNOWN",null,"MESSAGING_023"));
        service.execute(d.id(),1,proof);
        Delivery old=repository.get(d.id(),false).orElseThrow();
        assertThat(service.resolve(OWNER,d.id(),new Resolution("NOT_SENT","provider-audit-".repeat(10))).status()).isEqualTo("FAILED");
        repository.finish(d.id(),old.attemptId(),"PROVIDER_ACCEPTED","late",null,Instant.now());
        assertThat(service.get(OWNER,d.id()).status()).isEqualTo("FAILED");
        assertThat(service.retry(OWNER,d.id(),new Retry(1,false)).generation()).isEqualTo(2);
    }
    @Test void expiredAndCrashedAttemptsAreRecoveredWithoutResending() {
        View d=service.create(request()); grant(d,1);
        jdbc.update("UPDATE messaging_outbound SET status='SENDING',updated_ms=0 WHERE id=?",d.id().toString());
        service.relay();
        assertThat(service.get(OWNER,d.id()).status()).isEqualTo("UNKNOWN");
        View expired=service.create(request());
        jdbc.update("UPDATE messaging_outbound SET expires_ms=0 WHERE id=?",expired.id().toString());
        service.relay();
        assertThat(service.get(OWNER,expired.id()).status()).isEqualTo("EXPIRED");
        verifyNoInteractions(provider);
    }
    @Test void shortOrWrongLeaseCannotBeginProviderCall() {
        View d=service.create(request());
        when(scheduler.authorize(any())).thenReturn(new Grant(UUID.randomUUID(),d.id().toString(),"message-outbound:"+d.id()+":1",1,Instant.now().plusSeconds(10)));
        assertThatThrownBy(() -> service.execute(d.id(),1,proof)).isInstanceOf(OutboundException.class);
        when(scheduler.authorize(any())).thenReturn(new Grant(UUID.randomUUID(),UUID.randomUUID().toString(),"message-outbound:"+d.id()+":1",1,Instant.now().plusSeconds(120)));
        assertThatThrownBy(() -> service.execute(d.id(),1,proof)).isInstanceOf(OutboundException.class);
        verifyNoInteractions(provider);
    }
    @Test void validatesFeishuSerializedBytesAndRejectsHtml() throws Exception {
        var actual=new OutboundProvider(mock(org.springframework.mail.javamail.JavaMailSender.class),mock(com.yuyutian.mytools.messaging.config.MessagingProperties.class),new com.fasterxml.jackson.databind.ObjectMapper());
        when(provider.feishuPayload(any(),any(),anyLong())).thenAnswer(i -> actual.feishuPayload(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
        Destination d=service.bind(binding(Channel.FEISHU,"https://open.feishu.cn/open-apis/bot/v2/hook/1234567890123456"));
        Content text=new Content("News","\u65b0".repeat(5500),null);
        assertThatThrownBy(() -> service.create(new Create(OWNER,"too-big",d.id(),text,OutboundSecurity.hash(text),Instant.now().plusSeconds(3600))))
                .isInstanceOf(OutboundException.class);
        Content html=new Content("News","Text","<p>Text</p>");
        assertThatThrownBy(() -> service.create(new Create(OWNER,"html",d.id(),html,OutboundSecurity.hash(html),Instant.now().plusSeconds(3600))))
                .isInstanceOf(OutboundException.class);
        Content small=new Content("News","Small text",null);
        assertThat(service.create(new Create(OWNER,"small",d.id(),small,OutboundSecurity.hash(small),Instant.now().plusSeconds(3600))).status()).isEqualTo("ACCEPTED");
    }
    @Test void schedulerFailureBeforeSendingIsVisibleAndSafelyRetryable() {
        View d=service.create(request());
        service.relay();
        jdbc.update("UPDATE messaging_outbound SET next_attempt_ms=0");
        when(scheduler.status(any())).thenReturn("FAILED");
        service.relay();
        assertThat(service.get(OWNER,d.id()).status()).isEqualTo("FAILED");
        assertThat(service.retry(OWNER,d.id(),new Retry(1,false)).generation()).isEqualTo(2);
        verifyNoInteractions(provider);
    }
    @Test void cancellationBeforeUnknownCreateResponsePreventsLateSubmission() {
        Create request=request();
        View cancelled=service.cancelByKey(request);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(service.create(request).id()).isEqualTo(cancelled.id());
        service.relay();
        verifyNoInteractions(scheduler,provider);
    }
    @Test void retryLostResponseCannotAdvanceGenerationTwice() {
        View d=service.create(request()); grant(d,1);
        when(provider.send(any(),any())).thenReturn(new OutboundProvider.Result("FAILED",null,"MESSAGING_022"));
        service.execute(d.id(),1,proof);
        View retry=service.retry(OWNER,d.id(),new Retry(1,false)); grant(retry,2);
        service.execute(d.id(),2,proof);
        assertThat(service.retry(OWNER,d.id(),new Retry(1,false)).generation()).isEqualTo(2);
        assertThat(service.get(OWNER,d.id()).status()).isEqualTo("FAILED");
        verify(provider,times(2)).send(any(),any());
    }
    private Binding binding(Channel channel,String address) { return new Binding(OWNER,UUID.randomUUID().toString(),channel,"Inbox",address,"","verified-manually",true); }
    private Create request() {
        Destination d=service.bind(binding(Channel.EMAIL,"person@example.com"));
        Content content=new Content("Subject","Frozen news",null);
        return new Create(OWNER,UUID.randomUUID().toString(),d.id(),content,OutboundSecurity.hash(content),Instant.now().plusSeconds(3600));
    }
    private void grant(View d,int generation) { when(scheduler.authorize(any())).thenReturn(new Grant(UUID.randomUUID(),d.id().toString(),"message-outbound:"+d.id()+":"+generation,1,Instant.now().plusSeconds(120))); }
}
