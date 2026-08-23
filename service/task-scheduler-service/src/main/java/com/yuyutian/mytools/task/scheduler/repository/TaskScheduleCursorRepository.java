package com.yuyutian.mytools.task.scheduler.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 定时任务游标与多副本租约仓储。
 */
@Repository
public class TaskScheduleCursorRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建定时任务游标仓储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public TaskScheduleCursorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等初始化定义的首次触发时间。
     *
     * @param definitionId 定义标识
     * @param nextFireAt 首次触发时间
     */
    public void initialize(UUID definitionId, Instant nextFireAt) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO task_schedule_cursor
                    (task_definition_id, next_fire_at, updated_at) VALUES (?, ?, ?)
                    """, definitionId.toString(), Timestamp.from(nextFireAt), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 多副本并发初始化时保留最先写入的同一游标。
        }
    }

    /**
     * 查询定义当前游标。
     *
     * @param definitionId 定义标识
     * @return 可选游标
     */
    public Optional<ScheduleCursor> find(UUID definitionId) {
        return jdbcTemplate.query("""
                SELECT task_definition_id, next_fire_at, last_scheduled_at
                FROM task_schedule_cursor WHERE task_definition_id = ?
                """, (resultSet, rowNumber) -> new ScheduleCursor(
                UUID.fromString(resultSet.getString("task_definition_id")),
                resultSet.getTimestamp("next_fire_at").toInstant(),
                resultSet.getTimestamp("last_scheduled_at") == null ? null
                        : resultSet.getTimestamp("last_scheduled_at").toInstant()
        ), definitionId.toString()).stream().findFirst();
    }

    /**
     * 在游标到期且无有效租约时抢占处理权。
     *
     * @param definitionId 定义标识
     * @param owner 调度实例标识
     * @param now 当前时间
     * @param leaseUntil 租约结束时间
     * @return 是否抢占成功
     */
    public boolean claim(UUID definitionId, String owner, Instant now, Instant leaseUntil) {
        return jdbcTemplate.update("""
                UPDATE task_schedule_cursor
                SET lease_owner = ?, lease_until = ?, updated_at = ?
                WHERE task_definition_id = ? AND next_fire_at <= ?
                  AND (lease_until IS NULL OR lease_until < ?)
                """, owner, Timestamp.from(leaseUntil), Timestamp.from(now), definitionId.toString(),
                Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    /**
     * 提交新的调度游标并释放租约。
     *
     * @param definitionId 定义标识
     * @param owner 租约持有者
     * @param lastScheduledAt 最后处理时间
     * @param nextFireAt 下次触发时间
     */
    public void advance(UUID definitionId, String owner, Instant lastScheduledAt, Instant nextFireAt) {
        int updated = jdbcTemplate.update("""
                UPDATE task_schedule_cursor
                SET last_scheduled_at = ?, next_fire_at = ?, lease_owner = NULL, lease_until = NULL, updated_at = ?
                WHERE task_definition_id = ? AND lease_owner = ?
                """, Timestamp.from(lastScheduledAt), Timestamp.from(nextFireAt), Timestamp.from(Instant.now()),
                definitionId.toString(), owner);
        if (updated != 1) {
            throw new IllegalStateException("Schedule cursor lease is no longer owned");
        }
    }

    /**
     * 定时任务持久化游标。
     *
     * @param definitionId 定义标识
     * @param nextFireAt 下次触发时间
     * @param lastScheduledAt 最后处理时间
     */
    public record ScheduleCursor(UUID definitionId, Instant nextFireAt, Instant lastScheduledAt) {
    }
}
