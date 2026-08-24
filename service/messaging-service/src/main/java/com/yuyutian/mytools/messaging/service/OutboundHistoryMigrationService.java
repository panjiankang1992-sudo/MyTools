package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.LegacyOutboundMessageItem;
import com.yuyutian.mytools.messaging.model.LegacyAttachmentArchive;
import com.yuyutian.mytools.messaging.model.LegacyOutboundMigrationBatch;
import com.yuyutian.mytools.messaging.model.LegacyOutboundMigrationResult;
import com.yuyutian.mytools.messaging.model.LegacyOutboundReconciliation;
import com.yuyutian.mytools.messaging.repository.OutboundHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/** 校验并幂等导入历史发件归档，禁止重新投递。 */
@Service
public class OutboundHistoryMigrationService {
    private static final Pattern MIGRATION_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private final OutboundHistoryRepository repository;
    private final ObjectMapper objectMapper;

    /** 创建历史发件迁移服务。 */
    public OutboundHistoryMigrationService(OutboundHistoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 校验或导入一个有界历史发件批次。 */
    @Transactional
    public LegacyOutboundMigrationResult migrate(LegacyOutboundMigrationBatch batch) {
        int accepted = 0;
        int skipped = 0;
        int rejected = 0;
        MessageDigest collection = digest();
        Map<String, String> identities = new HashMap<>();
        for (LegacyOutboundMessageItem item : batch.items()) {
            validate(item);
            String payloadDigest = payloadDigest(item);
            update(collection, payloadDigest);
            String identity = item.sourceSystem() + "\u0000" + item.legacyMessageId();
            String previous = identities.putIfAbsent(identity, payloadDigest);
            if (previous != null) {
                if (previous.equals(payloadDigest)) { skipped++; } else { rejected++; }
                continue;
            }
            var existing = repository.find(item.sourceSystem(), item.legacyMessageId()).orElse(null);
            if (existing != null) {
                if (existing.payloadSha256().equals(payloadDigest)) { skipped++; } else { rejected++; }
                continue;
            }
            accepted++;
            if (!batch.dryRun()) { repository.insert(batch.migrationKey(), item, payloadDigest); }
        }
        return new LegacyOutboundMigrationResult(batch.dryRun(), accepted, skipped, rejected,
                HexFormat.of().formatHex(collection.digest()));
    }

    /** 返回指定迁移键的目标侧稳定集合证据。 */
    @Transactional(readOnly = true)
    public LegacyOutboundReconciliation reconcile(String migrationKey) {
        if (migrationKey == null || !MIGRATION_KEY.matcher(migrationKey).matches()) {
            throw new IllegalArgumentException("Migration key is invalid");
        }
        var records = repository.findAll(migrationKey);
        MessageDigest collection = digest();
        records.forEach(record -> update(collection, record.sourceSystem(), record.legacyMessageId(),
                record.payloadSha256()));
        return new LegacyOutboundReconciliation(migrationKey, records.size(),
                HexFormat.of().formatHex(collection.digest()));
    }

    private void validate(LegacyOutboundMessageItem item) {
        if ((item.bodyText() == null || item.bodyText().isBlank())
                && (item.bodyHtml() == null || item.bodyHtml().isBlank())) {
            throw new IllegalArgumentException("Historical message body is missing");
        }
        if ("SENT".equals(item.status()) && item.sentAt() == null) {
            throw new IllegalArgumentException("Sent timestamp is missing");
        }
        for (LegacyAttachmentArchive attachment : item.attachments()) {
            boolean archived = "ARCHIVED".equals(attachment.availability());
            boolean digestValid = attachment.sha256() != null
                    && attachment.sha256().matches("^[a-f0-9]{64}$");
            boolean referenceValid = digestValid && ("msgservice-archive://sha256/"
                    + attachment.sha256()).equals(attachment.archiveRef());
            if (archived && (attachment.size() == null || !referenceValid)) {
                throw new IllegalArgumentException("Archived attachment identity is invalid");
            }
            if (!archived && (attachment.legacyContentRef() == null
                    || attachment.legacyContentRef().isBlank()
                    || attachment.sha256() != null || attachment.archiveRef() != null)) {
                throw new IllegalArgumentException("Missing attachment evidence is invalid");
            }
        }
    }

    private String payloadDigest(LegacyOutboundMessageItem item) {
        try {
            return HexFormat.of().formatHex(digest().digest(objectMapper.writeValueAsBytes(item)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Historical message payload is invalid", exception);
        }
    }

    private void update(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
}
