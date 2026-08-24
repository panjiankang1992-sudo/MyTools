package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.LegacyReaderMigrationBatch;
import com.yuyutian.mytools.reader.model.LegacyReaderMigrationItem;
import com.yuyutian.mytools.reader.model.LegacyReaderMigrationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 将旧 MyTools 的不可再生 Reader 用户数据幂等导入新 schema。
 */
@Service
public class LegacyReaderMigrationService {

    private static final List<String> TYPES = List.of("SHELF", "PROGRESS", "MARKER");
    private static final long MAX_EPOCH_MILLIS = 253_402_300_799_999L;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建旧 Reader 数据迁移服务。
     */
    public LegacyReaderMigrationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验或导入一个有界迁移批次。
     */
    @Transactional
    public LegacyReaderMigrationResult migrate(LegacyReaderMigrationBatch batch) {
        int accepted = 0;
        int skipped = 0;
        List<String> rejected = new ArrayList<>();
        MessageDigest digest = sha256();
        for (LegacyReaderMigrationItem item : batch.items()) {
            String type = item.entityType().toUpperCase(Locale.ROOT);
            String auditKey = item.legacyKey();
            String idempotencyHash = hash(batch.migrationKey() + "\n" + type + "\n"
                    + item.ownerId() + "\n" + auditKey);
            String payload = json(normalizedPayload(item));
            String payloadSha256 = hash(type + "\n" + item.ownerId() + "\n" + auditKey + "\n" + payload);
            digest.update((type + ":" + item.ownerId() + ":" + auditKey + ":" + payloadSha256 + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            if (!TYPES.contains(type) || !valid(item)) {
                rejected.add(type + ":" + item.ownerId() + ":" + auditKey);
                continue;
            }
            if (batch.dryRun()) {
                accepted++;
                continue;
            }
            List<String> existingHashes = jdbcTemplate.queryForList("""
                    SELECT payload_sha256 FROM legacy_reader_migration_item
                    WHERE entity_type = ? AND owner_id = ? AND idempotency_hash = ?
                    """, String.class, type, item.ownerId(), idempotencyHash);
            if (!existingHashes.isEmpty()) {
                if (payloadSha256.equals(existingHashes.getFirst())) {
                    skipped++;
                } else {
                    rejected.add(type + ":" + item.ownerId() + ":" + auditKey);
                }
                continue;
            }
            UUID targetId = importItem(type, item, payload);
            jdbcTemplate.update("""
                    INSERT INTO legacy_reader_migration_item
                        (entity_type, owner_id, idempotency_hash, legacy_key, payload_sha256,
                         target_id, migrated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, type, item.ownerId(), idempotencyHash, auditKey, payloadSha256,
                    targetId == null ? null : targetId.toString(), Timestamp.from(Instant.now()));
            accepted++;
        }
        return new LegacyReaderMigrationResult(accepted, skipped, rejected.size(), List.copyOf(rejected),
                HexFormat.of().formatHex(digest.digest()));
    }

    private UUID importItem(String type, LegacyReaderMigrationItem item, String payload) {
        return switch (type) {
            case "SHELF" -> importShelf(item, payload);
            case "PROGRESS" -> importProgress(item, payload);
            case "MARKER" -> importMarker(item, payload);
            default -> throw new IllegalStateException("Legacy Reader migration type is invalid");
        };
    }

    private UUID importShelf(LegacyReaderMigrationItem item, String payload) {
        UUID id = stableId("shelf", item.ownerId(), item.legacyKey());
        jdbcTemplate.update("""
                INSERT INTO shelf_book
                    (id, owner_id, book_key, metadata_json, deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), item.ownerId(), item.legacyKey(), payload,
                item.deleted(), item.revision(),
                timestamp(item.serverUpdatedAt()), timestamp(item.serverUpdatedAt()));
        jdbcTemplate.update("""
                INSERT IGNORE INTO legacy_reader_key_map
                    (owner_id, legacy_book_id, legacy_book_id_sha256, shelf_book_id, sync_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, item.ownerId(), item.bookId(), hash(item.bookId()), id.toString(), item.legacyKey(),
                Timestamp.from(Instant.now()));
        return id;
    }

    private UUID importProgress(LegacyReaderMigrationItem item, String payload) {
        UUID shelfId = ensureShelf(item);
        jdbcTemplate.update("""
                INSERT INTO reading_progress
                    (shelf_book_id, chapter_index, chapter_url, position_json, deleted, version, updated_at)
                VALUES (?, 0, ?, ?, ?, ?, ?)
                """, shelfId.toString(), stringValue(item.payload(), "chapterTitle"), payload,
                item.deleted(), item.revision(), timestamp(item.serverUpdatedAt()));
        return shelfId;
    }

    private UUID importMarker(LegacyReaderMigrationItem item, String payload) {
        UUID shelfId = ensureShelf(item);
        UUID markerId = stableId("marker", item.ownerId(), item.legacyKey());
        jdbcTemplate.update("""
                INSERT INTO reader_marker
                    (id, shelf_book_id, marker_type, chapter_index, position_json, note_text,
                     deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                """, markerId.toString(), shelfId.toString(), stringValue(item.payload(), "kind"), payload,
                stringValue(item.payload(), "note"), item.deleted(), item.revision(),
                timestamp(longValue(item.payload(), "createdAt", item.serverUpdatedAt())),
                timestamp(item.serverUpdatedAt()));
        return markerId;
    }

    private UUID shelfId(long ownerId, String bookId) {
        List<String> ids = jdbcTemplate.queryForList("""
                SELECT shelf_book_id FROM legacy_reader_key_map
                WHERE owner_id = ? AND legacy_book_id_sha256 = ? AND legacy_book_id = ?
                """, String.class, ownerId, hash(bookId), bookId);
        if (ids.size() != 1) {
            throw new IllegalStateException("Legacy Reader shelf mapping is missing");
        }
        return UUID.fromString(ids.getFirst());
    }

    private UUID ensureShelf(LegacyReaderMigrationItem item) {
        List<String> ids = jdbcTemplate.queryForList("""
                SELECT shelf_book_id FROM legacy_reader_key_map
                WHERE owner_id = ? AND legacy_book_id_sha256 = ? AND legacy_book_id = ?
                """, String.class, item.ownerId(), hash(item.bookId()), item.bookId());
        if (ids.size() == 1) {
            return UUID.fromString(ids.getFirst());
        }
        if (!ids.isEmpty()) {
            throw new IllegalStateException("Legacy Reader shelf mapping is ambiguous");
        }
        UUID shelfId = stableId("placeholder", item.ownerId(), item.bookId());
        String syncKey = "placeholder:" + hash(item.bookId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "Unknown");
        metadata.put("legacyBookId", item.bookId());
        metadata.put("legacyPlaceholder", true);
        // 旧库可能只有阅读进度；创建确定性占位书架以保全不可再生位置。
        jdbcTemplate.update("""
                INSERT INTO shelf_book
                    (id, owner_id, book_key, metadata_json, deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, FALSE, 1, ?, ?)
                """, shelfId.toString(), item.ownerId(), syncKey, json(metadata),
                timestamp(item.serverUpdatedAt()), timestamp(item.serverUpdatedAt()));
        jdbcTemplate.update("""
                INSERT INTO legacy_reader_key_map
                    (owner_id, legacy_book_id, legacy_book_id_sha256, shelf_book_id, sync_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, item.ownerId(), item.bookId(), hash(item.bookId()), shelfId.toString(), syncKey,
                Timestamp.from(Instant.now()));
        return shelfId;
    }

    private boolean valid(LegacyReaderMigrationItem item) {
        if (item.revision() <= 0 || item.serverUpdatedAt() <= 0
                || item.serverUpdatedAt() > MAX_EPOCH_MILLIS
                || !(item.payload().get("deleted") instanceof Boolean deleted) || deleted != item.deleted()) {
            return false;
        }
        String type = item.entityType().toUpperCase(Locale.ROOT);
        if ("SHELF".equals(type)) {
            return item.legacyKey().length() <= 512;
        }
        if ("MARKER".equals(type)) {
            Object kind = item.payload().get("kind");
            return kind instanceof String value && !value.isBlank() && value.length() <= 32;
        }
        return true;
    }

    private UUID stableId(String type, long ownerId, String key) {
        return UUID.nameUUIDFromBytes(("mytools-reader:" + type + ":" + ownerId + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    private Timestamp timestamp(long epochMillis) {
        return Timestamp.from(Instant.ofEpochMilli(epochMillis));
    }

    private String stringValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    private long longValue(Map<String, Object> payload, String key, long fallback) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Legacy Reader migration payload is invalid", exception);
        }
    }

    private Map<String, Object> normalizedPayload(LegacyReaderMigrationItem item) {
        Map<String, Object> value = new LinkedHashMap<>(item.payload());
        value.put("legacyKey", item.legacyKey());
        value.put("legacyBookId", item.bookId());
        value.put("deleted", item.deleted());
        value.put("revision", item.revision());
        value.put("serverUpdatedAt", item.serverUpdatedAt());
        return value;
    }

    private String hash(String value) {
        return HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
