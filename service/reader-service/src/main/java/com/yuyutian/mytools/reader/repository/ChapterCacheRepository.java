package com.yuyutian.mytools.reader.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ChapterCacheBatchRequest;
import com.yuyutian.mytools.reader.model.ChapterCacheView;
import com.yuyutian.mytools.reader.model.ChapterPrefetchRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 章节预取请求与缓存仓储。
 */
@Repository
public class ChapterCacheRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建章节缓存仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 转换器
     */
    public ChapterCacheRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 新增预取请求。
     *
     * @param record 请求记录
     */
    public void insert(ChapterPrefetchRecord record) {
        jdbcTemplate.update("""
                INSERT INTO chapter_prefetch_request
                    (id, owner_id, idempotency_key, source_id, source_version, book_url, status,
                     task_instance_id, parameters_json, requested_count, cached_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id().toString(), record.ownerId(), record.idempotencyKey(),
                record.sourceId().toString(), record.sourceVersion(), record.bookUrl(), record.status(), null,
                writeJson(record.parameters()), record.requestedCount(), record.cachedCount(),
                Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
    }

    /**
     * 按幂等键查询请求。
     */
    public Optional<ChapterPrefetchRecord> findByIdempotencyKey(long ownerId, String key) {
        return query("WHERE owner_id = ? AND idempotency_key = ?", ownerId, key);
    }

    /**
     * 按标识查询请求。
     */
    public Optional<ChapterPrefetchRecord> findById(UUID id) {
        return query("WHERE id = ?", id.toString());
    }

    /**
     * 绑定调度任务。
     */
    public void bindTask(UUID requestId, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE chapter_prefetch_request SET task_instance_id = ?, status = 'QUEUED', updated_at = ?
                WHERE id = ?
                """, taskId.toString(), Timestamp.from(Instant.now()), requestId.toString());
    }

    /**
     * 更新请求汇总。
     */
    public void updateSummary(UUID requestId, String status) {
        Integer cached = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM chapter_prefetch_cache_link pcl JOIN chapter_cache cc
                  ON cc.id = pcl.chapter_cache_id
                WHERE pcl.prefetch_request_id = ? AND cc.expires_at > ?
                """, Integer.class, requestId.toString(), Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
                UPDATE chapter_prefetch_request SET status = ?, cached_count = ?, updated_at = ? WHERE id = ?
                """, status, cached == null ? 0 : cached, Timestamp.from(Instant.now()), requestId.toString());
    }

    /**
     * 幂等保存章节批次。
     *
     * @return 本批次数量
     */
    public int saveBatch(ChapterPrefetchRecord record, ChapterCacheBatchRequest request) {
        Instant now = Instant.now();
        String bookKey = bookKey(record.bookUrl());
        for (ChapterCacheBatchRequest.Chapter chapter : request.chapters()) {
            String chapterKey = bookKey(chapter.chapterUrl());
            String cacheId;
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM chapter_cache
                    WHERE owner_id = ? AND source_id = ? AND book_key = ? AND chapter_key = ?
                    """, Integer.class, record.ownerId(), record.sourceId().toString(), bookKey, chapterKey);
            Instant expiresAt = now.plusSeconds(Math.min(chapter.ttlSeconds(), 604800));
            if (count != null && count > 0) {
                cacheId = jdbcTemplate.queryForObject("""
                        SELECT id FROM chapter_cache
                        WHERE owner_id = ? AND source_id = ? AND book_key = ? AND chapter_key = ?
                        """, String.class, record.ownerId(), record.sourceId().toString(), bookKey, chapterKey);
                jdbcTemplate.update("""
                        UPDATE chapter_cache SET source_version = ?, chapter_index = ?, chapter_title = ?,
                            chapter_url = ?, content_text = ?, content_sha256 = ?, size_bytes = ?,
                            expires_at = ?, updated_at = ?
                        WHERE owner_id = ? AND source_id = ? AND book_key = ? AND chapter_key = ?
                        """, record.sourceVersion(), chapter.index(), chapter.title(), chapter.chapterUrl(),
                        chapter.content(), chapter.sha256(), chapter.sizeBytes(), Timestamp.from(expiresAt),
                        Timestamp.from(now), record.ownerId(), record.sourceId().toString(), bookKey, chapterKey);
            } else {
                cacheId = UUID.randomUUID().toString();
                jdbcTemplate.update("""
                        INSERT INTO chapter_cache
                            (id, owner_id, source_id, source_version, book_key, book_url, chapter_key,
                             chapter_url, chapter_index, chapter_title, content_text, content_sha256,
                             size_bytes, expires_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, cacheId, record.ownerId(), record.sourceId().toString(),
                        record.sourceVersion(), bookKey, record.bookUrl(), chapterKey, chapter.chapterUrl(),
                        chapter.index(), chapter.title(), chapter.content(), chapter.sha256(), chapter.sizeBytes(),
                        Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now));
            }
            Integer linked = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM chapter_prefetch_cache_link
                    WHERE prefetch_request_id = ? AND chapter_cache_id = ?
                    """, Integer.class, record.id().toString(), cacheId);
            if (linked == null || linked == 0) {
                jdbcTemplate.update("""
                        INSERT INTO chapter_prefetch_cache_link
                            (prefetch_request_id, chapter_cache_id, created_at) VALUES (?, ?, ?)
                        """, record.id().toString(), cacheId, Timestamp.from(now));
            }
        }
        return request.chapters().size();
    }

    /**
     * 查询未过期章节缓存。
     */
    public Optional<ChapterCacheView> findCached(long ownerId, UUID sourceId, String bookUrl, String chapterUrl) {
        return jdbcTemplate.query("""
                SELECT cc.* FROM chapter_cache cc JOIN book_source bs
                  ON bs.id = cc.source_id AND bs.current_version = cc.source_version
                WHERE cc.owner_id = ? AND cc.source_id = ? AND cc.book_key = ?
                  AND cc.chapter_key = ? AND cc.expires_at > ? AND bs.enabled = TRUE
                """, (resultSet, rowNumber) -> new ChapterCacheView(sourceId, resultSet.getString("book_url"),
                resultSet.getInt("chapter_index"), resultSet.getString("chapter_title"),
                resultSet.getString("chapter_url"), resultSet.getString("content_text"),
                resultSet.getString("content_sha256"), resultSet.getLong("size_bytes"),
                resultSet.getTimestamp("expires_at").toInstant()), ownerId, sourceId.toString(), bookKey(bookUrl),
                bookKey(chapterUrl), Timestamp.from(Instant.now())).stream().findFirst();
    }

    private Optional<ChapterPrefetchRecord> query(String clause, Object... arguments) {
        return jdbcTemplate.query("SELECT * FROM chapter_prefetch_request " + clause,
                (resultSet, rowNumber) -> {
                    String taskId = resultSet.getString("task_instance_id");
                    return new ChapterPrefetchRecord(UUID.fromString(resultSet.getString("id")),
                            resultSet.getLong("owner_id"), resultSet.getString("idempotency_key"),
                            UUID.fromString(resultSet.getString("source_id")), resultSet.getInt("source_version"),
                            resultSet.getString("book_url"), resultSet.getString("status"),
                            taskId == null ? null : UUID.fromString(taskId),
                            readJson(resultSet.getString("parameters_json")), resultSet.getInt("requested_count"),
                            resultSet.getInt("cached_count"), resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getTimestamp("updated_at").toInstant());
                }, arguments).stream().findFirst();
    }

    private String bookKey(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Chapter prefetch parameters cannot be serialized", exception);
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
            throw new IllegalArgumentException("Stored chapter prefetch parameters are invalid", exception);
        }
    }
}
