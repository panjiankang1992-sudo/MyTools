package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.service.MessageOutboundTaskAccess;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 提供方调用前查询实时租约，阻止过期执行和取消后的新发送。 */
@RestController
@RequestMapping("/api/v1/task-instances/messaging/executions")
public class MessageOutboundExecutionController {
    private final JdbcTemplate jdbc;
    /** 注入权威调度数据库。 */
    public MessageOutboundExecutionController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 当前运行、当前 fence 和有效租约必须同时满足。 */
    @PostMapping("/{id}/authorize")
    public ResponseEntity<Grant> authorize(@PathVariable UUID id, @RequestHeader("X-Task-Lease-Token") UUID token) {
        MessageOutboundTaskAccess.requireIdentity();
        var grant = jdbc.query("""
                SELECT t.id,t.business_id,t.idempotency_key,e.fencing_token,e.lease_until
                FROM task_execution e JOIN task_instance t ON t.id=e.task_instance_id
                WHERE e.id=? AND e.lease_token=? AND e.status='RUNNING' AND e.lease_until>?
                AND e.fencing_token>0 AND t.status='RUNNING' AND t.cancel_requested_at IS NULL
                AND t.task_name='message_send_channel_message' AND t.business_type='MESSAGE_OUTBOUND'
                AND NOT EXISTS(SELECT 1 FROM task_execution newer WHERE newer.task_instance_id=t.id AND newer.fencing_token>e.fencing_token)
                """, (r, i) -> new Grant(UUID.fromString(r.getString(1)), r.getString(2), r.getString(3), r.getLong(4), r.getTimestamp(5).toInstant()),
                id.toString(), token.toString(), Timestamp.from(Instant.now())).stream().findFirst()
                .orElseThrow(() -> new SchedulerException(ErrorCode.EXECUTION_LEASE_LOST, HttpStatus.CONFLICT, "Active outbound execution lease required"));
        return ResponseEntity.ok().header("Cache-Control", "no-store, private").body(grant);
    }
    /** 最小租约授权结果，不含收件信息和租约凭据。 */
    public record Grant(UUID taskId, String businessId, String idempotencyKey, long fencingToken, Instant leaseUntil) { }
}
