package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.LegacyKnownRecipientItem;
import com.yuyutian.mytools.messaging.model.LegacyMessageTemplateItem;
import com.yuyutian.mytools.messaging.model.LegacyReferenceDataBatch;
import com.yuyutian.mytools.messaging.model.LegacyReferenceDataReconciliation;
import com.yuyutian.mytools.messaging.model.LegacyReferenceDataResult;
import com.yuyutian.mytools.messaging.repository.ReferenceDataRepository;
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

/** 幂等迁移 MsgService 模板和已知收件人。 */
@Service
public class ReferenceDataMigrationService {
    private static final Pattern MIGRATION_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private final ReferenceDataRepository repository;
    private final ObjectMapper objectMapper;

    /** 创建参考数据迁移服务。 */
    public ReferenceDataMigrationService(ReferenceDataRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 校验或导入模板和已知收件人批次。 */
    @Transactional
    public LegacyReferenceDataResult migrate(LegacyReferenceDataBatch batch) {
        if (batch.templates().isEmpty() && batch.recipients().isEmpty()) {
            throw new IllegalArgumentException("Reference data batch is empty");
        }
        Counts templates = migrateTemplates(batch);
        Counts recipients = migrateRecipients(batch);
        MessageDigest collection = digest();
        templates.digests().forEach(value -> collection.update(HexFormat.of().parseHex(value)));
        recipients.digests().forEach(value -> collection.update(HexFormat.of().parseHex(value)));
        return new LegacyReferenceDataResult(batch.dryRun(), templates.accepted(), templates.skipped(),
                templates.rejected(), recipients.accepted(), recipients.skipped(), recipients.rejected(),
                HexFormat.of().formatHex(collection.digest()));
    }

    /** 返回指定迁移键的稳定目标侧证据。 */
    @Transactional(readOnly = true)
    public LegacyReferenceDataReconciliation reconcile(String migrationKey) {
        if (migrationKey == null || !MIGRATION_KEY.matcher(migrationKey).matches()) {
            throw new IllegalArgumentException("Migration key is invalid");
        }
        var records = repository.findMigrations(migrationKey);
        MessageDigest collection = digest();
        records.forEach(record -> update(collection, record.entityType(), record.sourceSystem(),
                record.legacyEntityId(), record.payloadSha256()));
        int templateCount = (int) records.stream().filter(record -> "TEMPLATE".equals(record.entityType())).count();
        return new LegacyReferenceDataReconciliation(migrationKey, templateCount,
                records.size() - templateCount, HexFormat.of().formatHex(collection.digest()));
    }

    private Counts migrateTemplates(LegacyReferenceDataBatch batch) {
        Counts counts = new Counts();
        for (LegacyMessageTemplateItem item : batch.templates()) {
            classify(counts, "TEMPLATE", item.sourceSystem(), item.legacyTemplateId(), item,
                    () -> repository.insertTemplate(batch.migrationKey(), item, payloadDigest(item)), batch.dryRun());
        }
        return counts;
    }

    private Counts migrateRecipients(LegacyReferenceDataBatch batch) {
        Counts counts = new Counts();
        for (LegacyKnownRecipientItem item : batch.recipients()) {
            classify(counts, "RECIPIENT", item.sourceSystem(), item.legacyRecipientId(), item,
                    () -> repository.insertRecipient(batch.migrationKey(), item, payloadDigest(item)), batch.dryRun());
        }
        return counts;
    }

    private void classify(Counts counts, String type, String source, String legacyId,
                          Object item, Runnable insert, boolean dryRun) {
        String value = payloadDigest(item);
        counts.digests().add(value);
        String identity = source + "\u0000" + legacyId;
        String previous = counts.identities().putIfAbsent(identity, value);
        if (previous != null) {
            if (previous.equals(value)) { counts.skipped++; } else { counts.rejected++; }
            return;
        }
        var existing = repository.findMigration(type, source, legacyId).orElse(null);
        if (existing != null) {
            if (existing.payloadSha256().equals(value)) { counts.skipped++; } else { counts.rejected++; }
            return;
        }
        counts.accepted++;
        if (!dryRun) { insert.run(); }
    }

    private String payloadDigest(Object value) {
        try { return HexFormat.of().formatHex(digest().digest(objectMapper.writeValueAsBytes(value))); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Reference data payload is invalid", exception);
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

    private static final class Counts {
        private int accepted;
        private int skipped;
        private int rejected;
        private final java.util.List<String> digests = new java.util.ArrayList<>();
        private final Map<String, String> identities = new HashMap<>();
        int accepted() { return accepted; }
        int skipped() { return skipped; }
        int rejected() { return rejected; }
        java.util.List<String> digests() { return digests; }
        Map<String, String> identities() { return identities; }
    }
}
