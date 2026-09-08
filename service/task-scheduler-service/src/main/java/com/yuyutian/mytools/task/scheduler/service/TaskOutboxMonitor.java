package com.yuyutian.mytools.task.scheduler.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Outbox 积压状态查询服务。
 */
@Service
public class TaskOutboxMonitor {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 Outbox 监测服务。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public TaskOutboxMonitor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前 Outbox 积压快照。
     *
     * @param now 当前时间
     * @return 积压快照
     */
    public OutboxSnapshot snapshot(Instant now) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS backlog,
                       MIN(CASE WHEN status <> 'DEAD' THEN created_at ELSE NULL END) AS oldest_created_at,
                       SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END) AS dead_count
                FROM task_outbox
                WHERE status IN ('PENDING', 'RETRY', 'PROCESSING', 'DEAD')
                """, (resultSet, rowNumber) -> {
            long backlog = resultSet.getLong("backlog") - resultSet.getLong("dead_count");
            long deadCount = resultSet.getLong("dead_count");
            Timestamp oldest = resultSet.getTimestamp("oldest_created_at");
            long oldestAgeSeconds = oldest == null ? 0
                    : Math.max(0, Duration.between(oldest.toInstant(), now).toSeconds());
            return new OutboxSnapshot(backlog, oldestAgeSeconds, deadCount);
        });
    }

    /**
     * Outbox 积压快照。
     *
     * @param backlog 待投递数量
     * @param oldestAgeSeconds 最早未完成事件年龄秒数
     * @param deadCount 死信数量
     */
    public record OutboxSnapshot(long backlog, long oldestAgeSeconds, long deadCount) {
    }
}
