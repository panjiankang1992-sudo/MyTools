package com.yuyutian.mytools.messaging.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboundProviderTest {
    private JavaMailSender sender;
    private OutboundProvider provider;
    private HttpServer server;
    private String url;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        sender=mock(JavaMailSender.class);
        var properties=mock(MessagingProperties.class); when(properties.mailFrom()).thenReturn("sender@example.com");
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        provider=new OutboundProvider(sender,properties,json);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.start(); url="http://127.0.0.1:"+server.getAddress().getPort()+"/hook";
    }
    @AfterEach void cleanup() { server.stop(0); }
    @Test void emailContainsBothAlternativesAndStableMessageId() throws Exception {
        Delivery d=delivery(Channel.EMAIL);
        assertThat(provider.send(d,new Secret("recipient@example.com", "")).status()).isEqualTo("PROVIDER_ACCEPTED");
        var captor=org.mockito.ArgumentCaptor.forClass(MimeMessage.class); verify(sender).send(captor.capture());
        MimeMessage message=captor.getValue();
        assertThat(message.getHeader("Message-ID",null)).isEqualTo("<"+d.id()+".1@mytools.messaging>");
        var bytes=new java.io.ByteArrayOutputStream(); message.writeTo(bytes);
        String wire=bytes.toString(StandardCharsets.UTF_8);
        assertThat(wire).contains("multipart/alternative", "text/plain", "text/html", "Frozen news", "<p>Frozen news</p>");
    }
    @Test void smtpExceptionIsUnknownAndDoesNotExposeRemoteDetails() {
        doThrow(new MailSendException("credential or destination must not leak")).when(sender).send(any(MimeMessage.class));
        assertThat(provider.send(delivery(Channel.EMAIL),new Secret("recipient@example.com", "")))
                .isEqualTo(new OutboundProvider.Result("UNKNOWN",null,"MESSAGING_023"));
    }
    @Test void feishuSignUsesOfficialEmptyMessageHmacAndNeutralizesMentions() throws Exception {
        var payload=json.readTree(provider.feishuPayload(new Content("News", "<at user_id=\"all\">hello</at>",null),"test-signing-secret",1700000000));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("1700000000\ntest-signing-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        assertThat(payload.path("sign").asText()).isEqualTo(java.util.Base64.getEncoder().encodeToString(mac.doFinal(new byte[0])));
        assertThat(payload.path("content").path("text").asText()).doesNotContain("<at");
    }
    @Test void feishuRequiresExplicitBusinessSuccess() {
        respond(200,"{\"code\":0}");
        assertThat(sendFeishu().status()).isEqualTo("PROVIDER_ACCEPTED");
        respond(200,"{\"code\":19024}");
        assertThat(sendFeishu().status()).isEqualTo("FAILED");
        respond(200,"{}");
        assertThat(sendFeishu().status()).isEqualTo("UNKNOWN");
    }
    @Test void feishuRejectsRateLimitAndPreservesUnknownForBadGateway() {
        respond(429,"{}"); assertThat(sendFeishu().status()).isEqualTo("FAILED");
        respond(503,"{}"); assertThat(sendFeishu().status()).isEqualTo("UNKNOWN");
        respond(302,"{}"); assertThat(sendFeishu().status()).isEqualTo("UNKNOWN");
    }
    @Test void oversizedOrInvalidProviderResponseCannotBecomeSuccess() {
        respond(200,"x".repeat(9000)); assertThat(sendFeishu().status()).isEqualTo("UNKNOWN");
        respond(200,"not-json"); assertThat(sendFeishu().status()).isEqualTo("UNKNOWN");
    }
    @Test void expiredContentNeverReachesSmtpPreparationOrNetwork() {
        Delivery d=withDeadline(delivery(Channel.EMAIL),Instant.now().minusSeconds(1));
        assertThat(provider.send(d,new Secret("fixture@example.test","")))
                .isEqualTo(new OutboundProvider.Result("EXPIRED",null,"MESSAGING_028"));
        verify(sender,never()).createMimeMessage();verify(sender,never()).send(any(MimeMessage.class));
    }
    @Test void deadlineIsRecheckedAfterMimePreparation() {
        Instant before=Instant.parse("2026-10-18T00:00:00Z"),deadline=before.plusSeconds(1);
        var clock=mock(java.time.Clock.class);when(clock.instant()).thenReturn(before,deadline);
        var properties=mock(MessagingProperties.class);when(properties.mailFrom()).thenReturn("sender@example.test");
        provider=new OutboundProvider(sender,properties,json,null,clock);
        assertThat(provider.send(withDeadline(delivery(Channel.EMAIL),deadline),new Secret("fixture@example.test","")).status()).isEqualTo("EXPIRED");
        verify(sender).createMimeMessage();verify(sender,never()).send(any(MimeMessage.class));
    }
    @Test void deadlineIsRecheckedAfterFeishuRateReservation() {
        Instant before=Instant.parse("2026-10-18T00:00:00Z"),deadline=before.plusSeconds(1);
        var clock=mock(java.time.Clock.class);when(clock.instant()).thenReturn(before,deadline);
        var limiter=mock(com.yuyutian.mytools.messaging.service.OutboundRateLimiter.class);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/hook",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});
        provider=new OutboundProvider(sender,mock(MessagingProperties.class),json,limiter,clock);
        assertThat(provider.send(withDeadline(delivery(Channel.FEISHU),deadline),new Secret(url,"")).status()).isEqualTo("EXPIRED");
        verify(limiter).reserve(eq(url),any());assertThat(requests.get()).isZero();
    }
    private Delivery withDeadline(Delivery d,Instant deadline){return new Delivery(d.id(),d.ownerId(),d.idempotencyKey(),d.destinationId(),d.channel(),d.content(),d.payloadHash(),d.status(),d.generation(),d.taskId(),d.attemptId(),d.lastFence(),d.expiresAt(),d.updatedAt(),d.errorCode(),d.contentIdentityHash(),deadline);}
    private OutboundProvider.Result sendFeishu() { return provider.send(delivery(Channel.FEISHU),new Secret(url,"")); }
    private void respond(int status,String body) {
        try { server.removeContext("/hook"); } catch (IllegalArgumentException ignored) { }
        server.createContext("/hook",exchange -> {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
    }
    private Delivery delivery(Channel channel) {
        return new Delivery(UUID.randomUUID(),1,"key",UUID.randomUUID(),channel,new Content("News","Frozen news",channel==Channel.EMAIL?"<p>Frozen news</p>":null),"hash","ACCEPTED",1,null,null,0,Instant.now().plusSeconds(3600),Instant.now(),null);
    }
}
