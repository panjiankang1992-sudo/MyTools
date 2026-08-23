package com.yuyutian.mytools.task.scheduler.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 聚合广播和分片任务的全部节点执行目标状态。
 */
@Service
public class MultiNodeTaskAggregationService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建多节点任务聚合服务。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public MultiNodeTaskAggregationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 聚合目标进度，并在全部目标结束后更新实例终态。
     *
     * @param taskInstanceId 任务实例标识
     * @param now 聚合时间
     */
    public void aggregate(UUID taskInstanceId, Instant now) {
        TargetAggregate aggregate = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN status IN ('QUEUED', 'RUNNING') THEN 1 ELSE 0 END) AS pending,
                       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                       SUM(CASE WHEN status = 'TIMED_OUT' THEN 1 ELSE 0 END) AS timed_out,
                       SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled
                FROM task_execution_target WHERE task_instance_id = ?
                """, (resultSet, rowNumber) -> new TargetAggregate(
                resultSet.getInt("total"), resultSet.getInt("pending"), resultSet.getInt("failed"),
                resultSet.getInt("timed_out"), resultSet.getInt("cancelled")
        ), taskInstanceId.toString());
        if (aggregate == null || aggregate.total() == 0) {
            return;
        }
        int completed = aggregate.total() - aggregate.pending();
        if (aggregate.pending() > 0) {
            jdbcTemplate.update("""
                    UPDATE task_instance SET progress = ?, updated_at = ?
                    WHERE id = ? AND status IN ('RUNNING', 'CANCELLING')
                    """, completed * 100 / aggregate.total(), Timestamp.from(now), taskInstanceId.toString());
            return;
        }
        String status = aggregate.failed() > 0 ? "FAILED"
                : aggregate.timedOut() > 0 ? "TIMED_OUT"
                : aggregate.cancelled() > 0 ? "CANCELLED" : "SUCCEEDED";
        jdbcTemplate.update("""
                UPDATE task_instance SET status = ?, progress = ?, updated_at = ?
                WHERE id = ? AND status IN ('RUNNING', 'CANCELLING')
                """, status, "SUCCEEDED".equals(status) ? 100 : 0, Timestamp.from(now), taskInstanceId.toString());
    }

    private record TargetAggregate(int total, int pending, int failed, int timedOut, int cancelled) {
    }
}
