package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.config.NodeHealthProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 执行节点与集群可用性监测服务。
 */
@Service
public class ExecutorNodeMonitor {

    private final JdbcTemplate jdbcTemplate;
    private final NodeHealthProperties properties;

    /**
     * 创建执行节点可用性监测服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param properties 节点健康配置
     */
    public ExecutorNodeMonitor(JdbcTemplate jdbcTemplate, NodeHealthProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 读取当前节点与集群可用性快照。
     *
     * @param now 当前时间
     * @return 可用性快照
     */
    public NodeAvailabilitySnapshot snapshot(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minusSeconds(properties.offlineAfterSeconds()));
        NodeCounts counts = jdbcTemplate.queryForObject("""
                SELECT
                  SUM(CASE WHEN enabled = TRUE AND status IN ('ONLINE', 'BUSY')
                                AND last_heartbeat_at >= ? THEN 1 ELSE 0 END) AS available_count,
                  SUM(CASE WHEN enabled = TRUE AND status = 'DRAINING' THEN 1 ELSE 0 END) AS draining_count,
                  SUM(CASE WHEN status = 'OFFLINE' THEN 1 ELSE 0 END) AS offline_count
                FROM executor_node
                """, (resultSet, rowNumber) -> new NodeCounts(
                resultSet.getLong("available_count"), resultSet.getLong("draining_count"),
                resultSet.getLong("offline_count")), cutoff);
        List<String> unavailableClusters = jdbcTemplate.query("""
                SELECT ec.name
                FROM execution_cluster ec
                WHERE ec.enabled = TRUE
                  AND NOT EXISTS (
                    SELECT 1 FROM cluster_node cn
                    JOIN executor_node en ON en.id = cn.node_id
                    WHERE cn.cluster_id = ec.id AND cn.enabled = TRUE AND en.enabled = TRUE
                      AND en.status IN ('ONLINE', 'BUSY') AND en.last_heartbeat_at >= ?
                  )
                ORDER BY ec.name
                """, (resultSet, rowNumber) -> resultSet.getString(1), cutoff);
        BlockedTasks blocked = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS blocked_count, MIN(ti.created_at) AS oldest_created_at
                FROM task_instance ti
                JOIN task_definition td ON td.id = ti.task_definition_id
                JOIN execution_cluster ec ON ec.id = td.cluster_id AND ec.enabled = TRUE
                WHERE ti.status = 'QUEUED'
                  AND NOT EXISTS (
                    SELECT 1 FROM cluster_node cn
                    JOIN executor_node en ON en.id = cn.node_id
                    WHERE cn.cluster_id = ec.id AND cn.enabled = TRUE AND en.enabled = TRUE
                      AND en.status IN ('ONLINE', 'BUSY') AND en.last_heartbeat_at >= ?
                  )
                """, (resultSet, rowNumber) -> {
            Timestamp oldest = resultSet.getTimestamp("oldest_created_at");
            long age = oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), now).toSeconds());
            return new BlockedTasks(resultSet.getLong("blocked_count"), age);
        }, cutoff);
        return new NodeAvailabilitySnapshot(counts.available(), counts.draining(), counts.offline(),
                unavailableClusters, blocked.count(), blocked.oldestAgeSeconds());
    }

    /**
     * 节点与集群可用性快照。
     *
     * @param availableNodes 可领取节点数
     * @param drainingNodes 排空节点数
     * @param offlineNodes 离线节点数
     * @param unavailableClusters 无可用节点集群名称
     * @param blockedQueuedTasks 因集群无可用节点而受阻的排队任务数
     * @param oldestBlockedAgeSeconds 最早受阻任务年龄秒数
     */
    public record NodeAvailabilitySnapshot(long availableNodes, long drainingNodes, long offlineNodes,
                                           List<String> unavailableClusters, long blockedQueuedTasks,
                                           long oldestBlockedAgeSeconds) {
    }

    private record NodeCounts(long available, long draining, long offline) {
    }

    private record BlockedTasks(long count, long oldestAgeSeconds) {
    }
}
