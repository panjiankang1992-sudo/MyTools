package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.service.NewsTaskAccess;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 新闻服务专用的实时执行校验，静态令牌和执行租约必须同时成立。 */
@RestController
@RequestMapping("/api/v1/task-instances/news/executions")
public class NewsExecutionAuthorizationController {
    private final JdbcTemplate jdbc;
    /** 注入 Scheduler 权威状态数据库。 */
    public NewsExecutionAuthorizationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 拒绝过期、取消、旧 fence 或其他任务的执行，不缓存授权结果。 */
    @PostMapping("/{executionId}/authorize")
    public ResponseEntity<Grant> authorize(@PathVariable UUID executionId, @RequestHeader("X-Task-Lease-Token") UUID leaseToken) {
        NewsTaskAccess.requireIdentity();
        var grant = jdbc.query("""
                SELECT t.id, t.business_id, t.idempotency_key, n.name, e.fencing_token, e.lease_until
                FROM task_execution e JOIN task_instance t ON t.id = e.task_instance_id
                JOIN executor_node n ON n.id = e.node_id
                WHERE e.id = ? AND e.lease_token = ? AND e.status = 'RUNNING'
                  AND e.lease_until > ? AND e.fencing_token > 0
                  AND t.status = 'RUNNING' AND t.cancel_requested_at IS NULL
                  AND ((t.task_name = 'news_generate_edition' AND t.business_type = 'NEWS_EDITION')
                       OR (t.task_name = 'news_resolve_region' AND t.business_type = 'NEWS_REGION')
                       OR (t.task_name = 'news_generate_weekly' AND t.business_type = 'NEWS_WEEKLY'))
                  AND NOT EXISTS (SELECT 1 FROM task_execution newer WHERE newer.task_instance_id = t.id AND newer.fencing_token > e.fencing_token)
                """, (row, index) -> new Grant(UUID.fromString(row.getString(1)), row.getString(2), row.getString(3), row.getString(4),
                row.getLong(5), row.getTimestamp(6).toInstant()), executionId.toString(), leaseToken.toString(), Timestamp.from(Instant.now()))
                .stream().findFirst().orElseThrow(() -> new SchedulerException(ErrorCode.EXECUTION_LEASE_LOST, HttpStatus.CONFLICT, "Active news execution lease required"));
        return ResponseEntity.ok().header("Cache-Control", "no-store, private").body(grant);
    }

    /** 最小授权范围，不返回租约令牌或任务正文。 */
    public record Grant(UUID taskId, String businessId, String idempotencyKey, String nodeId, long fencingToken, Instant leaseUntil) { }
}
