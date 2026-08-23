package com.yuyutian.mytools.reader.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.SearchMode;
import com.yuyutian.mytools.reader.model.SearchRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 书源搜索请求与聚合结果仓储。
 */
@Repository
public class SearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建搜索仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 转换器
     */
    public SearchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据幂等键查询请求。
     *
     * @param idempotencyKey 幂等键
     * @return 搜索请求
     */
    public Optional<SearchRecord> findByIdempotencyKey(String idempotencyKey) {
        return query("SELECT * FROM book_search_request WHERE idempotency_key = ?", idempotencyKey);
    }

    /**
     * 根据标识查询请求。
     *
     * @param id 请求标识
     * @return 搜索请求
     */
    public Optional<SearchRecord> findById(UUID id) {
        return query("SELECT * FROM book_search_request WHERE id = ?", id.toString());
    }

    /**
     * 新增待调度搜索请求。
     *
     * @param record 搜索请求
     */
    public void insert(SearchRecord record) {
        jdbcTemplate.update("""
                INSERT INTO book_search_request
                    (id, owner_id, idempotency_key, keyword, query_mode, page, status,
                     task_instance_id, parameters_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id().toString(), record.ownerId(), record.idempotencyKey(), record.keyword(),
                record.mode().name(), record.page(), record.status(), null, writeJson(record.parameters()),
                Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
    }

    /**
     * 绑定调度任务并更新状态。
     *
     * @param requestId 请求标识
     * @param taskId 任务标识
     * @param status 搜索状态
     */
    public void bindTask(UUID requestId, UUID taskId, String status) {
        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE book_search_request SET task_instance_id = ?, status = ?, updated_at = ? WHERE id = ?",
                taskId.toString(), status, Timestamp.from(now), requestId.toString());
        jdbcTemplate.update("""
                INSERT INTO book_search_task_binding
                    (search_request_id, task_instance_id, shard_key, created_at)
                VALUES (?, ?, ?, ?)
                """, requestId.toString(), taskId.toString(), "scheduler-native", Timestamp.from(now));
    }

    /**
     * 保存最新分片快照。
     *
     * @param requestId 请求标识
     * @param executionId 执行标识
     * @param targetIndex 目标序号
     * @param targetCount 目标总数
     * @param status 执行状态
     * @param result 分片结果
     */
    public void saveShard(UUID requestId, UUID executionId, Integer targetIndex, Integer targetCount,
                          String status, Map<String, Object> result) {
        Instant now = Instant.now();
        String json = writeJson(result);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM book_search_shard_result
                WHERE search_request_id = ? AND execution_id = ?
                """, Integer.class, requestId.toString(), executionId.toString());
        // 重试产生同一执行标识时仅覆盖该执行的最新状态。
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    UPDATE book_search_shard_result
                    SET target_index = ?, target_count = ?, status = ?, result_json = ?, updated_at = ?
                    WHERE search_request_id = ? AND execution_id = ?
                    """, targetIndex, targetCount, status, json, Timestamp.from(now),
                    requestId.toString(), executionId.toString());
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO book_search_shard_result
                    (id, search_request_id, execution_id, target_index, target_count, status,
                     result_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), requestId.toString(), executionId.toString(), targetIndex,
                targetCount, status, json, Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 原子替换聚合结果和请求状态。
     *
     * @param requestId 请求标识
     * @param status 聚合状态
     * @param results 去重结果
     */
    public void replaceAggregate(UUID requestId, String status, List<Map<String, Object>> results) {
        jdbcTemplate.update("DELETE FROM book_search_aggregate_result WHERE search_request_id = ?",
                requestId.toString());
        Instant now = Instant.now();
        for (Map<String, Object> result : results) {
            String canonicalKey = canonicalKey(result.get("name"));
            jdbcTemplate.update("""
                    INSERT INTO book_search_aggregate_result
                        (id, search_request_id, source_ref, canonical_book_key, result_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), requestId.toString(),
                    String.valueOf(result.getOrDefault("sourceId", "unknown")), canonicalKey,
                    writeJson(result), Timestamp.from(now), Timestamp.from(now));
        }
        jdbcTemplate.update("UPDATE book_search_request SET status = ?, updated_at = ? WHERE id = ?",
                status, Timestamp.from(now), requestId.toString());
    }

    /**
     * 查询已持久化的聚合结果。
     *
     * @param requestId 请求标识
     * @return 搜索结果
     */
    public List<Map<String, Object>> findAggregate(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT result_json FROM book_search_aggregate_result
                WHERE search_request_id = ? ORDER BY canonical_book_key
                """, (resultSet, rowNumber) -> readJson(resultSet.getString(1)), requestId.toString());
    }

    private Optional<SearchRecord> query(String sql, Object argument) {
        List<SearchRecord> records = jdbcTemplate.query(sql, (resultSet, rowNumber) -> {
            String taskId = resultSet.getString("task_instance_id");
            return new SearchRecord(UUID.fromString(resultSet.getString("id")), resultSet.getLong("owner_id"),
                    resultSet.getString("idempotency_key"), resultSet.getString("keyword"),
                    SearchMode.valueOf(resultSet.getString("query_mode")), resultSet.getInt("page"),
                    resultSet.getString("status"), taskId == null ? null : UUID.fromString(taskId),
                    readJson(resultSet.getString("parameters_json")),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        }, argument);
        return records.stream().findFirst();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Search result cannot be serialized", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String value) {
        try {
            var node = objectMapper.readTree(value);
            // H2 的 MySQL JSON 兼容模式会将绑定字符串再次编码，测试与生产均在此归一化。
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Stored search result is invalid", exception);
        }
    }

    private String canonicalKey(Object name) {
        return String.valueOf(name == null ? "" : name).toLowerCase().replaceAll("\\s+", "");
    }
}
