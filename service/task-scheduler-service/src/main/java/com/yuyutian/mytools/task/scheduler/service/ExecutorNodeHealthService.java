package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.config.NodeHealthProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 执行节点健康状态扫描服务。
 */
@Service
public class ExecutorNodeHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final NodeHealthProperties properties;

    /**
     * 创建执行节点健康状态扫描服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param properties 节点健康配置
     */
    public ExecutorNodeHealthService(JdbcTemplate jdbcTemplate, NodeHealthProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 定时将心跳超时节点转换为离线状态。
     */
    @Scheduled(fixedDelayString = "${task.node-health.scan-delay-ms:10000}")
    public void scanOfflineNodes() {
        scanOfflineNodes(Instant.now());
    }

    /**
     * 将指定时刻已经心跳超时的节点转换为离线状态。
     *
     * @param now 扫描基准时间
     * @return 转换的节点数量
     */
    @Transactional
    public int scanOfflineNodes(Instant now) {
        Instant cutoff = now.minusSeconds(properties.offlineAfterSeconds());
        return jdbcTemplate.update("""
                UPDATE executor_node
                SET status = 'OFFLINE', running_tasks = 0, status_changed_at = ?,
                    status_reason = 'HEARTBEAT_TIMEOUT', updated_at = ?
                WHERE enabled = TRUE AND status IN ('ONLINE', 'BUSY', 'DRAINING', 'UNHEALTHY')
                  AND NOT (status = 'DRAINING' AND COALESCE(status_reason, '') = 'QQ_FLOW_RELEASE')
                  AND last_heartbeat_at < ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(cutoff));
    }
}
