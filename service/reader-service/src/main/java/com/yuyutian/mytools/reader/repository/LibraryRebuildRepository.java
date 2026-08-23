package com.yuyutian.mytools.reader.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.LibraryIndexEntryView;
import com.yuyutian.mytools.reader.model.LibraryRebuildBatchResult;
import com.yuyutian.mytools.reader.model.LibraryRebuildRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 书库索引重建请求、暂存 generation 和已发布索引仓储。
 */
@Repository
public class LibraryRebuildRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建书库索引重建仓储。
     */
    public LibraryRebuildRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 新增重建请求及其不可见 generation。
     */
    public void insert(LibraryRebuildRecord record) {
        jdbcTemplate.update("""
                INSERT INTO library_rebuild_request
                    (id, owner_id, idempotency_key, snapshot_at, batch_size, status, task_instance_id,
                     indexed_count, last_cursor, last_error_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACCEPTED', NULL, 0, NULL, NULL, ?, ?)
                """, record.id().toString(), record.ownerId(), record.idempotencyKey(),
                Timestamp.from(record.snapshotAt()), record.batchSize(), Timestamp.from(record.createdAt()),
                Timestamp.from(record.updatedAt()));
        jdbcTemplate.update("""
                INSERT INTO library_index_generation
                    (id, owner_id, snapshot_at, active, entry_count, created_at, published_at)
                VALUES (?, ?, ?, FALSE, 0, ?, NULL)
                """, record.id().toString(), record.ownerId(), Timestamp.from(record.snapshotAt()),
                Timestamp.from(record.createdAt()));
    }

    /**
     * 按幂等键查询重建请求。
     */
    public Optional<LibraryRebuildRecord> findByKey(long ownerId, String key) {
        return query("WHERE owner_id = ? AND idempotency_key = ?", ownerId, key);
    }

    /**
     * 按标识查询重建请求。
     */
    public Optional<LibraryRebuildRecord> findById(UUID id) {
        return query("WHERE id = ?", id.toString());
    }

    /**
     * 绑定 Scheduler 任务。
     */
    public void bindTask(UUID id, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE library_rebuild_request SET task_instance_id = ?, status = 'QUEUED', updated_at = ?
                WHERE id = ? AND task_instance_id IS NULL
                """, taskId.toString(), Timestamp.from(Instant.now()), id.toString());
    }

    /**
     * 从冻结资产快照写入一个有界暂存批次。
     */
    public LibraryRebuildBatchResult rebuildBatch(LibraryRebuildRecord record) {
        String cursor = record.lastCursor() == null ? "" : record.lastCursor().toString();
        List<AssetRow> rows = jdbcTemplate.query("""
                SELECT id, title, author, format, storage_uri, content_sha256, metadata_json
                FROM ebook_asset WHERE owner_id = ? AND updated_at <= ? AND id > ?
                ORDER BY id LIMIT ?
                """, (resultSet, rowNumber) -> new AssetRow(resultSet.getString("id"),
                resultSet.getString("title"), resultSet.getString("author"), resultSet.getString("format"),
                resultSet.getString("storage_uri"), resultSet.getString("content_sha256"),
                resultSet.getString("metadata_json")), record.ownerId(), Timestamp.from(record.snapshotAt()),
                cursor, record.batchSize());
        int indexed = 0;
        for (AssetRow row : rows) {
            Integer existing = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM library_index_entry WHERE generation_id = ? AND book_key = ?
                    """, Integer.class, record.id().toString(), row.sha256());
            if (existing == null || existing == 0) {
                jdbcTemplate.update("""
                        INSERT INTO library_index_entry
                            (generation_id, ebook_asset_id, book_key, title, author, format, storage_uri,
                             content_sha256, metadata_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, record.id().toString(), row.id(), row.sha256(), row.title(), row.author(),
                        row.format(), row.storageUri(), row.sha256(), row.metadataJson(),
                        Timestamp.from(Instant.now()));
                indexed++;
            }
        }
        UUID next = rows.isEmpty() ? record.lastCursor() : UUID.fromString(rows.getLast().id());
        jdbcTemplate.update("""
                UPDATE library_rebuild_request SET status = 'RUNNING', indexed_count = indexed_count + ?,
                    last_cursor = ?, updated_at = ? WHERE id = ?
                """, indexed, next == null ? null : next.toString(), Timestamp.from(Instant.now()),
                record.id().toString());
        LibraryRebuildRecord updated = findById(record.id()).orElseThrow();
        return new LibraryRebuildBatchResult(indexed, updated.indexedCount(), next,
                rows.size() < record.batchSize());
    }

    /**
     * 原子发布完成的 generation。
     */
    public void publish(UUID id) {
        LibraryRebuildRecord record = findById(id).orElseThrow();
        Integer remaining = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ebook_asset WHERE owner_id = ? AND updated_at <= ? AND id > ?
                """, Integer.class, record.ownerId(), Timestamp.from(record.snapshotAt()),
                record.lastCursor() == null ? "" : record.lastCursor().toString());
        if (remaining != null && remaining > 0) {
            throw new IllegalStateException("Library rebuild snapshot has unprocessed assets");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE library_index_generation SET active = FALSE WHERE owner_id = ? AND active = TRUE",
                record.ownerId());
        jdbcTemplate.update("""
                UPDATE library_index_generation SET active = TRUE, entry_count = ?, published_at = ? WHERE id = ?
                """, record.indexedCount(), Timestamp.from(now), id.toString());
        jdbcTemplate.update("""
                UPDATE library_rebuild_request SET status = 'SUCCEEDED', last_error_code = NULL, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(now), id.toString());
    }

    /**
     * 设置未发布重建任务终态。
     */
    public void finish(UUID id, String status, String errorCode) {
        jdbcTemplate.update("""
                UPDATE library_rebuild_request SET status = ?, last_error_code = ?, updated_at = ?
                WHERE id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                """, status, errorCode, Timestamp.from(Instant.now()), id.toString());
    }

    /**
     * 查询当前已发布书库索引。
     */
    public List<LibraryIndexEntryView> activeIndex(long ownerId) {
        return jdbcTemplate.query("""
                SELECT lie.* FROM library_index_entry lie JOIN library_index_generation lig
                  ON lig.id = lie.generation_id
                WHERE lig.owner_id = ? AND lig.active = TRUE ORDER BY lie.title, lie.ebook_asset_id
                """, (resultSet, rowNumber) -> new LibraryIndexEntryView(
                UUID.fromString(resultSet.getString("ebook_asset_id")), resultSet.getString("book_key"),
                resultSet.getString("title"), resultSet.getString("author"), resultSet.getString("format"),
                resultSet.getString("storage_uri"), resultSet.getString("content_sha256"),
                readJson(resultSet.getString("metadata_json"))), ownerId);
    }

    private Optional<LibraryRebuildRecord> query(String clause, Object... arguments) {
        return jdbcTemplate.query("SELECT * FROM library_rebuild_request " + clause, (resultSet, rowNumber) -> {
            String taskId = resultSet.getString("task_instance_id");
            String cursor = resultSet.getString("last_cursor");
            return new LibraryRebuildRecord(UUID.fromString(resultSet.getString("id")),
                    resultSet.getLong("owner_id"), resultSet.getString("idempotency_key"),
                    resultSet.getTimestamp("snapshot_at").toInstant(), resultSet.getInt("batch_size"),
                    resultSet.getString("status"), taskId == null ? null : UUID.fromString(taskId),
                    resultSet.getLong("indexed_count"), cursor == null ? null : UUID.fromString(cursor),
                    resultSet.getString("last_error_code"), resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        }, arguments).stream().findFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            // 兼容 MySQL JSON 与 H2 MySQL 模式返回的一层或多层字符串包装。
            while (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Stored library metadata is invalid", exception);
        }
    }

    private record AssetRow(String id, String title, String author, String format, String storageUri,
                            String sha256, String metadataJson) {
    }
}
