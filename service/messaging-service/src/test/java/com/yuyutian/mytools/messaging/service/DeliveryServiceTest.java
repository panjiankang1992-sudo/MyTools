package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateDeliveryRequest;
import com.yuyutian.mytools.messaging.model.CreateInboundMessageRequest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class DeliveryServiceTest {

    @Autowired
    private DeliveryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @MockBean
    private JavaMailSender mailSender;

    @Test
    void shouldCreateAndExecuteOneIdempotentEmailDelivery() {
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createDeliveryTask(any(), any())).thenReturn(taskId);
        when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new MimeMessage(Session.getInstance(new Properties())));
        var request = new CreateDeliveryRequest(7L, "registration-code:7", ChannelType.EMAIL, null,
                "recipient@example.com", "Code", "123456");

        var created = service.create(request);
        var duplicate = service.create(request);
        var delivered = service.execute(created.id());
        var replayed = service.execute(created.id());

        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(replayed.providerMessageId()).isEqualTo(delivered.providerMessageId());
        assertThat(delivered.providerMessageId()).contains(created.id().toString());
        assertThat(service.get(created.id()).recipient()).isEqualTo("recipient@example.com");
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messaging_outbox", Integer.class)).isEqualTo(2);
    }

    @Test
    void shouldDeduplicateInboundMessageAndWriteOneEvent() {
        var request = new CreateInboundMessageRequest(8L, ChannelType.EMAIL, "external-1", "thread-1",
                "sender@example.com", "Subject", "Body", Instant.now());

        var first = service.receive(request);
        var duplicate = service.receive(request);

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inbound_message WHERE owner_id = 8", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messaging_outbox WHERE event_type = 'MessageReceived' AND aggregate_id=?",
                Integer.class, first.id().toString())).isEqualTo(1);
    }

    @Test
    void shouldPageInboundMessagesWithoutLeakingOtherOwners() {
        var older = service.receive(new CreateInboundMessageRequest(18L, ChannelType.EMAIL, "page-older", "thread",
                "sender@example.com", "Older", "Body", Instant.parse("2026-08-23T00:00:00Z")));
        var newer = service.receive(new CreateInboundMessageRequest(18L, ChannelType.EMAIL, "page-newer", "thread",
                "sender@example.com", "Newer", "Body", Instant.parse("2026-08-24T00:00:00Z")));
        var foreign = service.receive(new CreateInboundMessageRequest(19L, ChannelType.EMAIL, "page-foreign", "thread",
                "sender@example.com", "Foreign", "Body", Instant.parse("2026-08-25T00:00:00Z")));

        var first = service.listInbound(18L, null, 1);
        var second = service.listInbound(18L, first.nextAfterId(), 1);

        assertThat(first.items()).extracting(value -> value.id()).containsExactly(newer.id());
        assertThat(second.items()).extracting(value -> value.id()).containsExactly(older.id());
        assertThat(service.inbound(newer.id(), 18L).id()).isEqualTo(newer.id());
        assertThatThrownBy(() -> service.inbound(foreign.id(), 18L))
                .isInstanceOf(InboundMessageNotFoundException.class);
    }
}
