package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateInboundMessagePart;
import com.yuyutian.mytools.messaging.model.LegacyInboundMessageItem;
import com.yuyutian.mytools.messaging.model.LegacyInboundMigrationBatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InboundHistoryMigrationServiceTest {
    @Autowired
    private InboundHistoryMigrationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldDryRunImportReplayAndRejectConflictWithoutRealtimeEvent() {
        String legacyId = UUID.randomUUID().toString();
        LegacyInboundMessageItem item = item(legacyId, "historical body");

        var dryRun = service.migrate(new LegacyInboundMigrationBatch("msg-history-v1", true, List.of(item)));
        var applied = service.migrate(new LegacyInboundMigrationBatch("msg-history-v1", false, List.of(item)));
        var replayed = service.migrate(new LegacyInboundMigrationBatch("msg-history-v1", false, List.of(item)));
        var conflict = service.migrate(new LegacyInboundMigrationBatch(
                "msg-history-v1", false, List.of(item(legacyId, "changed body"))));

        assertThat(dryRun.accepted()).isEqualTo(1);
        assertThat(applied.accepted()).isEqualTo(1);
        assertThat(applied.digestSha256()).isEqualTo(dryRun.digestSha256());
        assertThat(replayed.skipped()).isEqualTo(1);
        assertThat(conflict.rejected()).isEqualTo(1);
        String externalId = "legacy:MSGSERVICE:" + legacyId;
        String messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM inbound_message WHERE external_message_id = ?", String.class, externalId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inbound_history_migration WHERE legacy_message_id = ?",
                Integer.class, legacyId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messaging_outbox WHERE aggregate_id = ? AND event_type = 'MessageReceived'",
                Integer.class, messageId)).isZero();
    }

    @Test
    void shouldClassifyDuplicateItemsConsistentlyInDryRunAndApply() {
        String legacyId = UUID.randomUUID().toString();
        LegacyInboundMessageItem item = item(legacyId, "same body");
        LegacyInboundMigrationBatch dryBatch = new LegacyInboundMigrationBatch(
                "msg-history-v2", true, List.of(item, item));
        LegacyInboundMigrationBatch applyBatch = new LegacyInboundMigrationBatch(
                "msg-history-v2", false, List.of(item, item));

        var dryRun = service.migrate(dryBatch);
        var applied = service.migrate(applyBatch);

        assertThat(dryRun.accepted()).isEqualTo(1);
        assertThat(dryRun.skipped()).isEqualTo(1);
        assertThat(applied.accepted()).isEqualTo(1);
        assertThat(applied.skipped()).isEqualTo(1);
        assertThat(applied.digestSha256()).isEqualTo(dryRun.digestSha256());
    }

    private LegacyInboundMessageItem item(String legacyId, String body) {
        return new LegacyInboundMessageItem("MSGSERVICE", legacyId, 7L, ChannelType.EMAIL,
                "mailbox:inbox", "sender@example.com", "subject", body,
                Instant.parse("2026-01-02T03:04:05Z"),
                List.of(new CreateInboundMessagePart("TEXT", body, null, null,
                        null, null, null, null)));
    }
}
