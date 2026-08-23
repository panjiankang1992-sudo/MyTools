package com.yuyutian.mytools.reader.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.EbookImportRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 电子书导入请求和领域资产仓储。
 */
@Repository
public class EbookImportRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建电子书导入仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 转换器
     */
    public EbookImportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 新增导入请求。
     *
     * @param record 导入记录
     */
    public void insert(EbookImportRecord record) {
        jdbcTemplate.update("""
                INSERT INTO ebook_import_request
                    (id, owner_id, idempotency_key, source_id, source_version, book_url,
                     requested_title, requested_author, storage_root, status, task_instance_id,
                     parameters_json, result_title, result_author, storage_uri, output_size,
                     output_sha256, chapter_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id().toString(), record.ownerId(), record.idempotencyKey(),
                record.sourceId().toString(), record.sourceVersion(), record.bookUrl(), record.title(),
                record.author(), record.storageRoot(), record.status(), null, writeJson(record.parameters()),
                null, null, null, null, null, null,
                Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
    }

    /**
     * 按所有者和幂等键查询导入请求。
     *
     * @param ownerId 所有者标识
     * @param idempotencyKey 幂等键
     * @return 导入记录
     */
    public Optional<EbookImportRecord> findByIdempotencyKey(long ownerId, String idempotencyKey) {
        return query("WHERE owner_id = ? AND idempotency_key = ?", ownerId, idempotencyKey);
    }

    /**
     * 按标识查询导入请求。
     *
     * @param id 请求标识
     * @return 导入记录
     */
    public Optional<EbookImportRecord> findById(UUID id) {
        return query("WHERE id = ?", id.toString());
    }

    /**
     * 绑定调度任务。
     *
     * @param requestId 请求标识
     * @param taskId 任务标识
     */
    public void bindTask(UUID requestId, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE ebook_import_request SET task_instance_id = ?, status = 'QUEUED', updated_at = ?
                WHERE id = ?
                """, taskId.toString(), Timestamp.from(Instant.now()), requestId.toString());
    }

    /**
     * 更新非成功任务状态。
     *
     * @param requestId 请求标识
     * @param status 任务状态
     */
    public void updateStatus(UUID requestId, String status) {
        jdbcTemplate.update("UPDATE ebook_import_request SET status = ?, updated_at = ? WHERE id = ?",
                status, Timestamp.from(Instant.now()), requestId.toString());
    }

    /**
     * 持久化成功导入结果并幂等登记电子书资产。
     *
     * @param record 导入记录
     * @param result 脚本结果
     */
    public void succeed(EbookImportRecord record, Map<String, Object> result) {
        String title = String.valueOf(result.get("title"));
        String author = String.valueOf(result.getOrDefault("author", ""));
        String storageUri = String.valueOf(result.get("storageUri"));
        long size = ((Number) result.get("size")).longValue();
        String sha256 = String.valueOf(result.get("sha256"));
        int chapters = ((Number) result.get("chapterCount")).intValue();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE ebook_import_request SET status = 'SUCCEEDED', result_title = ?, result_author = ?,
                    storage_uri = ?, output_size = ?, output_sha256 = ?, chapter_count = ?, updated_at = ?
                WHERE id = ?
                """, title, author, storageUri, size, sha256, chapters, Timestamp.from(now), record.id().toString());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ebook_asset WHERE import_request_id = ?", Integer.class, record.id().toString());
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO ebook_asset
                    (id, import_request_id, owner_id, source_id, title, author, format, storage_uri,
                     size_bytes, content_sha256, chapter_count, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'TXT', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), record.id().toString(), record.ownerId(),
                record.sourceId().toString(), title, author, storageUri, size, sha256, chapters,
                writeJson(Map.of("sourceVersion", record.sourceVersion(), "bookUrl", record.bookUrl())),
                Timestamp.from(now), Timestamp.from(now));
    }

    private Optional<EbookImportRecord> query(String condition, Object... arguments) {
        return jdbcTemplate.query("SELECT * FROM ebook_import_request " + condition, (resultSet, rowNumber) -> {
            String taskId = resultSet.getString("task_instance_id");
            String resultTitle = resultSet.getString("result_title");
            String resultAuthor = resultSet.getString("result_author");
            return new EbookImportRecord(UUID.fromString(resultSet.getString("id")),
                    resultSet.getLong("owner_id"), resultSet.getString("idempotency_key"),
                    UUID.fromString(resultSet.getString("source_id")), resultSet.getInt("source_version"),
                    resultSet.getString("book_url"), resultTitle == null ? resultSet.getString("requested_title") : resultTitle,
                    resultAuthor == null ? resultSet.getString("requested_author") : resultAuthor,
                    resultSet.getString("storage_root"), resultSet.getString("status"),
                    taskId == null ? null : UUID.fromString(taskId), readJson(resultSet.getString("parameters_json")),
                    resultSet.getString("storage_uri"), resultSet.getObject("output_size", Long.class),
                    resultSet.getString("output_sha256"), resultSet.getObject("chapter_count", Integer.class),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        }, arguments).stream().findFirst();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Ebook import data cannot be serialized", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Stored ebook import parameters are invalid", exception);
        }
    }
}
