package com.yuyutian.mytools.reader.repository;

import com.yuyutian.mytools.reader.model.CacheMaintenanceRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 章节缓存维护任务仓储。
 */
@Repository
public class CacheMaintenanceRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建章节缓存维护仓储。
     */
    public CacheMaintenanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增缓存维护任务。
     */
    public void insert(CacheMaintenanceRecord record) {
        jdbcTemplate.update("""
                INSERT INTO chapter_cache_maintenance
                    (id, idempotency_key, maintenance_type, cutoff_at, batch_size, status, task_instance_id,
                     deleted_count, last_error_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 0, NULL, ?, ?)
                """, record.id().toString(), record.idempotencyKey(), record.maintenanceType(),
                Timestamp.from(record.cutoffAt()), record.batchSize(), record.status(),
                Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
    }

    /**
     * 按幂等键查询维护任务。
     */
    public Optional<CacheMaintenanceRecord> findByKey(String key) {
        return query("WHERE idempotency_key = ?", key);
    }

    /**
     * 按标识查询维护任务。
     */
    public Optional<CacheMaintenanceRecord> findById(UUID id) {
        return query("WHERE id = ?", id.toString());
    }

    /**
     * 绑定 Scheduler 任务。
     */
    public void bindTask(UUID id, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE chapter_cache_maintenance SET task_instance_id = ?, status = 'QUEUED', updated_at = ?
                WHERE id = ? AND task_instance_id IS NULL
                """, taskId.toString(), Timestamp.from(Instant.now()), id.toString());
    }

    /**
     * 删除一个受限缓存批次并累计计数。
     */
    public int deleteBatch(CacheMaintenanceRecord record) {
        List<String> ids = candidateIds(record);
        if (ids.isEmpty()) {
            return 0;
        }
        for (String id : ids) {
            // 先删除请求关联，避免外键阻止缓存回收。
            jdbcTemplate.update("DELETE FROM chapter_prefetch_cache_link WHERE chapter_cache_id = ?", id);
            jdbcTemplate.update("DELETE FROM chapter_cache WHERE id = ?", id);
        }
        jdbcTemplate.update("""
                UPDATE chapter_cache_maintenance SET status = 'RUNNING', deleted_count = deleted_count + ?,
                    updated_at = ? WHERE id = ?
                """, ids.size(), Timestamp.from(Instant.now()), record.id().toString());
        return ids.size();
    }

    /**
     * 幂等设置维护任务终态。
     */
    public void finish(UUID id, String status, String errorCode) {
        jdbcTemplate.update("""
                UPDATE chapter_cache_maintenance SET status = ?, last_error_code = ?, updated_at = ?
                WHERE id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                """, status, errorCode, Timestamp.from(Instant.now()), id.toString());
    }

    private List<String> candidateIds(CacheMaintenanceRecord record) {
        if ("EXPIRED".equals(record.maintenanceType())) {
            return jdbcTemplate.query("""
                    SELECT id FROM chapter_cache WHERE expires_at <= ? ORDER BY expires_at, id LIMIT ?
                    """, (resultSet, rowNumber) -> resultSet.getString("id"),
                    Timestamp.from(record.cutoffAt()), record.batchSize());
        }
        return jdbcTemplate.query("""
                SELECT cc.id FROM chapter_cache cc JOIN book_source bs ON bs.id = cc.source_id
                WHERE cc.created_at <= ? AND (bs.enabled = FALSE OR cc.source_version <> bs.current_version)
                ORDER BY cc.created_at, cc.id LIMIT ?
                """, (resultSet, rowNumber) -> resultSet.getString("id"),
                Timestamp.from(record.cutoffAt()), record.batchSize());
    }

    private Optional<CacheMaintenanceRecord> query(String clause, Object... arguments) {
        return jdbcTemplate.query("SELECT * FROM chapter_cache_maintenance " + clause,
                (resultSet, rowNumber) -> {
                    String taskId = resultSet.getString("task_instance_id");
                    return new CacheMaintenanceRecord(UUID.fromString(resultSet.getString("id")),
                            resultSet.getString("idempotency_key"), resultSet.getString("maintenance_type"),
                            resultSet.getTimestamp("cutoff_at").toInstant(), resultSet.getInt("batch_size"),
                            resultSet.getString("status"), taskId == null ? null : UUID.fromString(taskId),
                            resultSet.getLong("deleted_count"), resultSet.getString("last_error_code"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getTimestamp("updated_at").toInstant());
                }, arguments).stream().findFirst();
    }
}
