package com.yuyutian.mytools.reader.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.DiscoveryRecord;
import com.yuyutian.mytools.reader.model.SourceIngestResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 书源发现请求和版本化书源仓储。
 */
@Repository
public class DiscoveryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建书源发现仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 转换器
     */
    public DiscoveryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 新增发现请求。
     *
     * @param record 发现请求
     */
    public void insert(DiscoveryRecord record) {
        jdbcTemplate.update("""
                INSERT INTO book_source_discovery_request
                    (id, owner_id, idempotency_key, source_url, status, task_instance_id,
                     processed_count, saved_count, rejected_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id().toString(), record.ownerId(), record.idempotencyKey(), record.url(),
                record.status(), null, record.processed(), record.saved(), record.rejected(),
                Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
    }

    /**
     * 按所有者和幂等键查询发现请求。
     *
     * @param ownerId 所有者标识
     * @param idempotencyKey 幂等键
     * @return 发现请求
     */
    public Optional<DiscoveryRecord> findByIdempotencyKey(long ownerId, String idempotencyKey) {
        return query("SELECT * FROM book_source_discovery_request WHERE owner_id = ? AND idempotency_key = ?",
                ownerId, idempotencyKey);
    }

    /**
     * 按标识查询发现请求。
     *
     * @param id 请求标识
     * @return 发现请求
     */
    public Optional<DiscoveryRecord> findById(UUID id) {
        return query("SELECT * FROM book_source_discovery_request WHERE id = ?", id.toString());
    }

    /**
     * 绑定调度任务。
     *
     * @param requestId 请求标识
     * @param taskId 任务标识
     */
    public void bindTask(UUID requestId, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE book_source_discovery_request
                SET task_instance_id = ?, status = 'QUEUED', updated_at = ? WHERE id = ?
                """, taskId.toString(), Timestamp.from(Instant.now()), requestId.toString());
    }

    /**
     * 更新调度结果摘要。
     *
     * @param requestId 请求标识
     * @param status 状态
     * @param processed 已处理数量
     * @param saved 已保存数量
     * @param rejected 已拒绝数量
     */
    public void updateSummary(UUID requestId, String status, int processed, int saved, int rejected) {
        jdbcTemplate.update("""
                UPDATE book_source_discovery_request
                SET status = ?, processed_count = ?, saved_count = ?, rejected_count = ?, updated_at = ?
                WHERE id = ?
                """, status, processed, saved, rejected, Timestamp.from(Instant.now()), requestId.toString());
    }

    /**
     * 保存一批发现书源并维护不可变版本。
     *
     * @param requestId 发现请求标识
     * @param sources 书源快照
     * @return 批次结果
     */
    public SourceIngestResult saveSources(UUID requestId, List<Map<String, Object>> sources) {
        DiscoveryRecord request = findById(requestId).orElseThrow();
        int saved = 0;
        int rejected = 0;
        for (Map<String, Object> source : sources) {
            String sourceUrl = text(source.get("bookSourceUrl"));
            String sourceName = text(source.get("bookSourceName"));
            if (sourceUrl.isBlank() || sourceName.isBlank() || sourceUrl.length() > 2000
                    || sourceName.length() > 300) {
                rejected++;
                continue;
            }
            String snapshot = writeJson(source);
            String contentHash = sha256(snapshot);
            String syncKey = sha256(sourceUrl);
            List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                    SELECT id, current_version FROM book_source WHERE owner_id = ? AND sync_key = ?
                    """, request.ownerId(), syncKey);
            if (existing.isEmpty()) {
                UUID sourceId = UUID.randomUUID();
                Instant now = Instant.now();
                jdbcTemplate.update("""
                        INSERT INTO book_source
                            (id, owner_id, sync_key, name, source_url, enabled, current_version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, TRUE, 1, ?, ?)
                        """, sourceId.toString(), request.ownerId(), syncKey, sourceName, sourceUrl,
                        Timestamp.from(now), Timestamp.from(now));
                insertVersion(sourceId, 1, snapshot, contentHash, now);
                saved++;
                continue;
            }
            UUID sourceId = UUID.fromString(String.valueOf(existing.getFirst().get("id")));
            int currentVersion = ((Number) existing.getFirst().get("current_version")).intValue();
            String currentHash = jdbcTemplate.queryForObject("""
                    SELECT content_sha256 FROM book_source_version WHERE book_source_id = ? AND version = ?
                    """, String.class, sourceId.toString(), currentVersion);
            // 内容未变化时只刷新可见名称和地址，不制造重复版本。
            if (!contentHash.equals(currentHash)) {
                int nextVersion = currentVersion + 1;
                insertVersion(sourceId, nextVersion, snapshot, contentHash, Instant.now());
                currentVersion = nextVersion;
            }
            jdbcTemplate.update("""
                    UPDATE book_source SET name = ?, source_url = ?, enabled = TRUE,
                        current_version = ?, updated_at = ? WHERE id = ?
                    """, sourceName, sourceUrl, currentVersion, Timestamp.from(Instant.now()), sourceId.toString());
            saved++;
        }
        jdbcTemplate.update("""
                UPDATE book_source_discovery_request
                SET saved_count = saved_count + ?, rejected_count = rejected_count + ?, updated_at = ? WHERE id = ?
                """, saved, rejected, Timestamp.from(Instant.now()), requestId.toString());
        return new SourceIngestResult(saved, rejected);
    }

    /**
     * 查询所有者拥有的书源当前执行快照。
     *
     * @param ownerId 所有者标识
     * @param sourceId 书源标识
     * @return 书源执行快照
     */
    public Optional<SourceExecutionSnapshot> findExecutionSnapshot(long ownerId, UUID sourceId) {
        return jdbcTemplate.query("""
                SELECT bs.id, bs.source_url, bs.current_version, bsv.snapshot_json
                FROM book_source bs JOIN book_source_version bsv
                  ON bsv.book_source_id = bs.id AND bsv.version = bs.current_version
                WHERE bs.owner_id = ? AND bs.id = ? AND bs.enabled = TRUE
                """, (resultSet, rowNumber) -> new SourceExecutionSnapshot(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("source_url"),
                resultSet.getInt("current_version"), readJson(resultSet.getString("snapshot_json"))),
                ownerId, sourceId.toString()).stream().findFirst();
    }

    private Optional<DiscoveryRecord> query(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> {
            String taskId = resultSet.getString("task_instance_id");
            return new DiscoveryRecord(UUID.fromString(resultSet.getString("id")),
                    resultSet.getLong("owner_id"), resultSet.getString("idempotency_key"),
                    resultSet.getString("source_url"), resultSet.getString("status"),
                    taskId == null ? null : UUID.fromString(taskId), resultSet.getInt("processed_count"),
                    resultSet.getInt("saved_count"), resultSet.getInt("rejected_count"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        }, arguments).stream().findFirst();
    }

    private void insertVersion(UUID sourceId, int version, String snapshot, String hash, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO book_source_version
                    (book_source_id, version, snapshot_json, content_sha256, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, sourceId.toString(), version, snapshot, hash, Timestamp.from(createdAt));
    }

    private String writeJson(Map<String, Object> source) {
        try {
            return objectMapper.writeValueAsString(source);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Book source snapshot cannot be serialized", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String text(Object value) {
        return value instanceof String text ? text.strip() : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String value) {
        try {
            var node = objectMapper.readTree(value);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Stored book source snapshot is invalid", exception);
        }
    }

    /**
     * 任务使用的不可变书源版本。
     *
     * @param id 书源标识
     * @param sourceUrl 书源地址
     * @param version 当前版本
     * @param snapshot 规则快照
     */
    public record SourceExecutionSnapshot(UUID id, String sourceUrl, int version, Map<String, Object> snapshot) {
    }
}
