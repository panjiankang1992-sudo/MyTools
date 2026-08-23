package com.yuyutian.mytools.reader.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.HealthCheckRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 书源健康检查请求与结果仓储。
 */
@Repository
public class HealthCheckRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建健康检查仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 转换器
     */
    public HealthCheckRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载所有启用书源的当前版本快照。
     *
     * @param ownerId 所有者标识
     * @return 执行参数书源列表
     */
    public List<Map<String, Object>> findEnabledSources(long ownerId) {
        return jdbcTemplate.query("""
                SELECT bs.id, bs.source_url, bsv.snapshot_json
                FROM book_source bs
                JOIN book_source_version bsv
                  ON bsv.book_source_id = bs.id AND bsv.version = bs.current_version
                WHERE bs.owner_id = ? AND bs.enabled = TRUE
                ORDER BY bs.id
                """, (resultSet, rowNumber) -> {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", resultSet.getString("id"));
            source.put("url", resultSet.getString("source_url"));
            source.put("snapshot", readJson(resultSet.getString("snapshot_json")));
            return source;
        }, ownerId);
    }

    /**
     * 新增健康检查请求。
     *
     * @param record 检查记录
     */
    public void insert(HealthCheckRecord record) {
        jdbcTemplate.update("""
                INSERT INTO book_source_health_check
                    (id, owner_id, idempotency_key, keyword, status, task_instance_id, parameters_json,
                     checked_count, healthy_count, unhealthy_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id().toString(), record.ownerId(), record.idempotencyKey(), record.keyword(),
                record.status(), null, writeJson(record.parameters()), record.checked(), record.healthy(),
                record.unhealthy(), Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
    }

    /**
     * 按幂等键查询健康检查。
     *
     * @param ownerId 所有者标识
     * @param idempotencyKey 幂等键
     * @return 检查记录
     */
    public Optional<HealthCheckRecord> findByIdempotencyKey(long ownerId, String idempotencyKey) {
        return query("SELECT * FROM book_source_health_check WHERE owner_id = ? AND idempotency_key = ?",
                ownerId, idempotencyKey);
    }

    /**
     * 按标识查询健康检查。
     *
     * @param id 请求标识
     * @return 检查记录
     */
    public Optional<HealthCheckRecord> findById(UUID id) {
        return query("SELECT * FROM book_source_health_check WHERE id = ?", id.toString());
    }

    /**
     * 绑定任务实例。
     *
     * @param requestId 请求标识
     * @param taskId 任务标识
     */
    public void bindTask(UUID requestId, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE book_source_health_check SET task_instance_id = ?, status = 'QUEUED', updated_at = ?
                WHERE id = ?
                """, taskId.toString(), Timestamp.from(Instant.now()), requestId.toString());
    }

    /**
     * 保存某个书源的最新检查结果。
     *
     * @param requestId 检查请求标识
     * @param result 脚本结果行
     */
    public void saveResult(UUID requestId, Map<String, Object> result) {
        UUID sourceId = UUID.fromString(String.valueOf(result.get("sourceId")));
        String status = String.valueOf(result.get("status"));
        long latency = result.get("latencyMillis") instanceof Number number ? Math.max(0, number.longValue()) : 0;
        String errorCode = result.get("errorCode") == null ? null : String.valueOf(result.get("errorCode"));
        Instant now = Instant.now();
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM book_source_health_result WHERE health_check_id = ? AND source_id = ?
                """, Integer.class, requestId.toString(), sourceId.toString());
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    UPDATE book_source_health_result
                    SET status = ?, latency_millis = ?, error_code = ?, checked_at = ?
                    WHERE health_check_id = ? AND source_id = ?
                    """, status, latency, errorCode, Timestamp.from(now), requestId.toString(), sourceId.toString());
        } else {
            jdbcTemplate.update("""
                    INSERT INTO book_source_health_result
                        (health_check_id, source_id, status, latency_millis, error_code, checked_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, requestId.toString(), sourceId.toString(), status, latency, errorCode, Timestamp.from(now));
        }
        // 健康检查只记录观测值，不修改用户维护的 enabled 状态。
        jdbcTemplate.update("""
                UPDATE book_source SET health_status = ?, health_latency_millis = ?, health_error_code = ?,
                    health_checked_at = ?, updated_at = ? WHERE id = ?
                """, status, latency, errorCode, Timestamp.from(now), Timestamp.from(now), sourceId.toString());
    }

    /**
     * 更新检查汇总状态。
     *
     * @param requestId 请求标识
     * @param status 任务状态
     * @param checked 已检查数量
     * @param healthy 健康数量
     * @param unhealthy 异常数量
     */
    public void updateSummary(UUID requestId, String status, int checked, int healthy, int unhealthy) {
        jdbcTemplate.update("""
                UPDATE book_source_health_check
                SET status = ?, checked_count = ?, healthy_count = ?, unhealthy_count = ?, updated_at = ?
                WHERE id = ?
                """, status, checked, healthy, unhealthy, Timestamp.from(Instant.now()), requestId.toString());
    }

    private Optional<HealthCheckRecord> query(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> {
            String taskId = resultSet.getString("task_instance_id");
            return new HealthCheckRecord(UUID.fromString(resultSet.getString("id")),
                    resultSet.getLong("owner_id"), resultSet.getString("idempotency_key"),
                    resultSet.getString("keyword"), resultSet.getString("status"),
                    taskId == null ? null : UUID.fromString(taskId),
                    readJson(resultSet.getString("parameters_json")), resultSet.getInt("checked_count"),
                    resultSet.getInt("healthy_count"), resultSet.getInt("unhealthy_count"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        }, arguments).stream().findFirst();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Health check parameters cannot be serialized", exception);
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
            throw new IllegalArgumentException("Stored health check parameters are invalid", exception);
        }
    }
}
