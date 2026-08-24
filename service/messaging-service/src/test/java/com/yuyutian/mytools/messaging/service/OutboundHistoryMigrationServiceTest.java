package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.LegacyAttachmentArchive;
import com.yuyutian.mytools.messaging.model.LegacyOutboundMessageItem;
import com.yuyutian.mytools.messaging.model.LegacyOutboundMigrationBatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OutboundHistoryMigrationServiceTest {
    @Autowired private OutboundHistoryMigrationService service;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void shouldArchiveWithoutCreatingDeliveryOrOutbox() {
        String legacyId = UUID.randomUUID().toString();
        var item = item(legacyId, "body");

        var dryRun = service.migrate(new LegacyOutboundMigrationBatch("msg-outbound-v1", true, List.of(item)));
        var applied = service.migrate(new LegacyOutboundMigrationBatch("msg-outbound-v1", false, List.of(item)));
        var replayed = service.migrate(new LegacyOutboundMigrationBatch("msg-outbound-v1", false, List.of(item)));
        var conflict = service.migrate(new LegacyOutboundMigrationBatch(
                "msg-outbound-v1", false, List.of(item(legacyId, "changed"))));

        assertThat(dryRun.accepted()).isEqualTo(1);
        assertThat(applied.accepted()).isEqualTo(1);
        assertThat(replayed.skipped()).isEqualTo(1);
        assertThat(conflict.rejected()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbound_message_history WHERE legacy_message_id=?",
                Integer.class, legacyId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_request", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messaging_outbox", Integer.class)).isZero();
    }

    @Test
    void shouldRequireContentAddressedAttachmentReference() {
        var attachment = new LegacyAttachmentArchive("report.txt", "text/plain", 4,
                "invalid", "msgservice-archive://attachment/report.txt");
        var item = new LegacyOutboundMessageItem("MSGSERVICE", UUID.randomUUID().toString(), 0L,
                ChannelType.EMAIL, "SENT", "sender@example.com", List.of("recipient@example.com"),
                "subject", "body", null, List.of(attachment), null, null, null,
                Instant.parse("2026-08-22T01:02:03Z"), Instant.parse("2026-08-22T01:02:00Z"));

        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(item))
                    .anyMatch(violation -> violation.getPropertyPath().toString().contains("attachments"));
        }
    }

    private LegacyOutboundMessageItem item(String legacyId, String body) {
        return new LegacyOutboundMessageItem("MSGSERVICE", legacyId, 0L, ChannelType.EMAIL, "SENT",
                "sender@example.com", List.of("recipient@example.com"), "subject", body, null,
                List.of(new LegacyAttachmentArchive("report.txt", "text/plain", 4,
                        "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                        "msgservice-archive://sha256/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")),
                "template-1", "provider-1", null, Instant.parse("2026-08-22T01:02:03Z"),
                Instant.parse("2026-08-22T01:02:00Z"));
    }
}
