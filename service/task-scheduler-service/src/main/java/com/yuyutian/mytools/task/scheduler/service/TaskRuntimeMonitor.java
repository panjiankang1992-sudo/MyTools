package com.yuyutian.mytools.task.scheduler.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * 调度队列与执行运行态指标查询服务。
 */
@Service
public class TaskRuntimeMonitor {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建任务运行态监测服务。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public TaskRuntimeMonitor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询排队任务数量。
     *
     * @return 排队任务数量
     */
    public long queueDepth() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE status = 'QUEUED'", Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 查询最早排队任务的等待秒数。
     *
     * @return 最早排队任务等待秒数
     */
    public long oldestQueueWaitSeconds() {
        Timestamp oldest = jdbcTemplate.queryForObject(
                "SELECT MIN(updated_at) FROM task_instance WHERE status = 'QUEUED'", Timestamp.class);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), Instant.now()).toSeconds());
    }

    /**
     * 查询正在运行的执行数量，数据库结果为容量权威。
     *
     * @return 正在运行的执行数量
     */
    public long runningExecutions() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE status = 'RUNNING'", Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 查询累计失租执行数量。
     *
     * @return 累计失租执行数量
     */
    public long leaseLostExecutions() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE lease_lost_at IS NOT NULL", Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 查询指定终态的已完成执行数量。
     *
     * @param status 执行终态
     * @return 已完成执行数量
     */
    public long completedExecutionCount(String status) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_execution
                WHERE status = ? AND started_at IS NOT NULL AND finished_at IS NOT NULL
                """, Long.class, status);
        return value == null ? 0 : value;
    }

    /**
     * 查询指定终态的累计执行秒数。
     *
     * @param status 执行终态
     * @return 累计执行秒数
     */
    public double completedExecutionSeconds(String status) {
        Number value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(TIMESTAMPDIFF(MICROSECOND, started_at, finished_at)), 0)
                FROM task_execution
                WHERE status = ? AND started_at IS NOT NULL AND finished_at IS NOT NULL
                """, Number.class, status);
        return value == null ? 0 : value.doubleValue() / 1_000_000D;
    }
}
