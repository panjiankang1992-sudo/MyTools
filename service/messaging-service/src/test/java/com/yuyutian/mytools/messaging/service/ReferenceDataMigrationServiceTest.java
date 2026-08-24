package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.LegacyKnownRecipientItem;
import com.yuyutian.mytools.messaging.model.LegacyMessageTemplateItem;
import com.yuyutian.mytools.messaging.model.LegacyReferenceDataBatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReferenceDataMigrationServiceTest {
    @Autowired private ReferenceDataMigrationService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void shouldDryRunApplyReplayAndRejectConflict() throws Exception {
        var template = template("template-1", "subject");
        var recipient = recipient("recipient-1", "recipient@example.com");
        var batch = new LegacyReferenceDataBatch("msg-reference-v1", true,
                List.of(template), List.of(recipient));

        var dryRun = service.migrate(batch);
        var applied = service.migrate(new LegacyReferenceDataBatch(
                "msg-reference-v1", false, List.of(template), List.of(recipient)));
        var replay = service.migrate(new LegacyReferenceDataBatch(
                "msg-reference-v1", false, List.of(template), List.of(recipient)));
        var conflict = service.migrate(new LegacyReferenceDataBatch(
                "msg-reference-v1", false, List.of(template("template-1", "changed")), List.of()));

        assertThat(dryRun.acceptedTemplates()).isEqualTo(1);
        assertThat(dryRun.acceptedRecipients()).isEqualTo(1);
        assertThat(applied.digestSha256()).isEqualTo(dryRun.digestSha256());
        assertThat(replay.skippedTemplates()).isEqualTo(1);
        assertThat(replay.skippedRecipients()).isEqualTo(1);
        assertThat(conflict.rejectedTemplates()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message_template", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM known_recipient", Integer.class)).isEqualTo(1);
        assertThat(service.reconcile("msg-reference-v1").templateCount()).isEqualTo(1);
        assertThat(service.reconcile("msg-reference-v1").recipientCount()).isEqualTo(1);
    }

    private LegacyMessageTemplateItem template(String id, String subject) throws Exception {
        return new LegacyMessageTemplateItem("MSGSERVICE", id, 0L, ChannelType.EMAIL,
                "verification", "description", subject, "body", null,
                objectMapper.readTree("{\"code\":{\"type\":\"string\"}}"),
                Instant.parse("2026-08-22T01:00:00Z"), Instant.parse("2026-08-22T01:01:00Z"));
    }

    private LegacyKnownRecipientItem recipient(String id, String address) {
        return new LegacyKnownRecipientItem("MSGSERVICE", id, 0L, ChannelType.EMAIL,
                address, "Recipient", Instant.parse("2026-08-22T01:00:00Z"));
    }
}
