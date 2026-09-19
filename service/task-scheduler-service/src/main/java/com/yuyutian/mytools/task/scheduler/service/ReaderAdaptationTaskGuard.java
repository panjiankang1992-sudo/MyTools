package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.config.TaskSecurityProperties;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 改编任务的确定性调度契约及取消屏障，旧通用创建入口也必须经过此守卫。 */
@Component
public class ReaderAdaptationTaskGuard {
    public static final String TASK_NAME = "reader_adapt_novel_chapter";
    private final JdbcTemplate jdbc;
    private final TaskSecurityProperties security;

    /** 注入持久屏障与已认证服务身份配置。 */
    public ReaderAdaptationTaskGuard(JdbcTemplate jdbc, TaskSecurityProperties security) {
        this.jdbc = jdbc;
        this.security = security;
    }

    /** 只接受独立配置的 Reader 服务凭据；共享令牌及关闭认证不是本功能的降级路径。 */
    public void requireReaderClient() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!security.required() || !security.businessClients().containsKey("reader-service")
                || !(attributes instanceof ServletRequestAttributes servlet)
                || !"reader-service".equals(servlet.getRequest().getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE))) {
            throw new SchedulerException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Reader service identity is required");
        }
    }

    /** 生成唯一允许的调度请求，参数仅包含业务版本 ID。 */
    public CreateTaskRequest request(UUID adaptationId) {
        return new CreateTaskRequest(TASK_NAME, key(adaptationId), "READER_CHAPTER_ADAPTATION", adaptationId.toString(),
                null, 40, Map.of("adaptationId", adaptationId.toString()), Map.of("reader.adaptation", "enabled"));
    }

    /** 返回不含用户意图或正文的确定性幂等键。 */
    public static String key(UUID adaptationId) {
        return TASK_NAME + ":" + adaptationId + ":v1";
    }

    /** 在创建事务中锁定取消屏障，拒绝额外参数、错误业务范围和已取消的迟到请求。 */
    public void beforeCreate(CreateTaskRequest incoming) {
        if (!protectedRequest(incoming)) {
            return;
        }
        requireReaderClient();
        UUID adaptationId = resource(incoming);
        if (!request(adaptationId).equals(incoming)) {
            throw new SchedulerException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, "Adaptation dispatch contract is invalid");
        }
        if (lock(adaptationId).get("cancel_requested_at") != null) {
            throw new SchedulerException(ErrorCode.TASK_DISPATCH_CANCELLED, HttpStatus.GONE, "Adaptation dispatch was cancelled");
        }
    }

    /** 创建与回放都固定同一任务身份，取消屏障不允许指向另一任务。 */
    public TaskInstanceView bind(CreateTaskRequest incoming, TaskInstanceView task) {
        if (!protectedRequest(incoming)) {
            return task;
        }
        UUID adaptationId = resource(incoming);
        int changed = jdbc.update("""
                UPDATE reader_adaptation_dispatch_guard SET task_instance_id = ?, updated_at = ?
                WHERE adaptation_id = ? AND cancel_requested_at IS NULL AND (task_instance_id IS NULL OR task_instance_id = ?)
                """, task.id().toString(), Timestamp.from(Instant.now()), adaptationId.toString(), task.id().toString());
        if (changed != 1) {
            throw new SchedulerException(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT, "Adaptation dispatch task conflicts");
        }
        return task;
    }

    /** 已有改编任务不能由其他业务服务通过通用接口访问或取消。 */
    public void requireAccess(TaskInstanceView task) {
        if (TASK_NAME.equalsIgnoreCase(task.taskName()) || (task.idempotencyKey() != null
                && task.idempotencyKey().regionMatches(true, 0, TASK_NAME + ":", 0, TASK_NAME.length() + 1))) {
            requireReaderClient();
        }
    }

    /** 查询及取消前复核原始契约，数据库大小写折叠也不能把别的任务当作改编任务。 */
    public void requireTask(UUID adaptationId, TaskInstanceView task) {
        if (task == null) {
            return;
        }
        CreateTaskRequest stored = new CreateTaskRequest(task.taskName(), task.idempotencyKey(), task.businessType(), task.businessId(),
                task.parentTaskInstanceId(), task.priority(), task.parameters(), task.requiredNodeLabels());
        if (!request(adaptationId).equals(stored)) {
            throw new SchedulerException(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT, "Adaptation dispatch task conflicts");
        }
    }

    /** 取消与创建持有同一业务键行锁，屏障在没有任务时也可建立。 */
    public Map<String, Object> lock(UUID adaptationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new SchedulerException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Dispatch guard requires a transaction");
        }
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    INSERT INTO reader_adaptation_dispatch_guard (adaptation_id, idempotency_key, created_at, updated_at)
                    VALUES (?, ?, ?, ?)
                    """, adaptationId.toString(), key(adaptationId).getBytes(StandardCharsets.UTF_8), Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            // 同一个业务键已有可用锁行，不创建新的调度身份。
        }
        return jdbc.queryForMap("SELECT * FROM reader_adaptation_dispatch_guard WHERE adaptation_id = ? FOR UPDATE", adaptationId.toString());
    }

    /** 持久记录取消，不删除屏障；即使模型功能或任务定义关闭仍可重复取消。 */
    public void recordCancellation(UUID adaptationId) {
        jdbc.update("""
                UPDATE reader_adaptation_dispatch_guard SET cancel_requested_at = COALESCE(cancel_requested_at, ?), updated_at = ?
                WHERE adaptation_id = ?
                """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), adaptationId.toString());
    }

    /** 查询取消屏障，不通过读操作建立新状态。 */
    public boolean cancellationRecorded(UUID adaptationId) {
        List<Boolean> flags = jdbc.query("SELECT cancel_requested_at IS NOT NULL FROM reader_adaptation_dispatch_guard WHERE adaptation_id = ?",
                (row, index) -> row.getBoolean(1), adaptationId.toString());
        return !flags.isEmpty() && flags.getFirst();
    }

    private static boolean protectedRequest(CreateTaskRequest request) {
        return TASK_NAME.equalsIgnoreCase(request.taskName()) || (request.idempotencyKey() != null
                && request.idempotencyKey().regionMatches(true, 0, TASK_NAME + ":", 0, TASK_NAME.length() + 1));
    }

    private static UUID resource(CreateTaskRequest request) {
        try {
            UUID id = UUID.fromString(request.businessId());
            if (!id.toString().equals(request.businessId())) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new SchedulerException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, "Adaptation dispatch resource is invalid");
        }
    }
}
