package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.WorkloadAuthorizationProperties;
import com.yuyutian.mytools.task.scheduler.model.ClaimedTaskView;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatRequest;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatView;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.WorkloadAssertion;
import com.yuyutian.mytools.task.scheduler.model.WorkloadIntrospection;
import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 权威租约与资源授权的同事务桥梁；不访问 Reader 数据，也不保存签名 token。 */
@Service
public class TaskExecutionAuthorizationService {
    private final JdbcTemplate jdbc;
    private final JsonColumnMapper json;
    private final WorkloadAuthorizationProperties properties;
    private final WorkloadAssertionSigner signer;
    private final WorkloadTlsIdentity tls;
    private final Clock clock;

    /** 注入签名、TLS 身份与数据库依赖。 */
    @Autowired
    public TaskExecutionAuthorizationService(JdbcTemplate jdbc, JsonColumnMapper json, WorkloadAuthorizationProperties properties,
                                             WorkloadAssertionSigner signer, WorkloadTlsIdentity tls) {
        this(jdbc, json, properties, signer, tls, Clock.systemUTC());
    }

    /** 测试控制时间而不跳过租约或 TLS 验证。 */
    public TaskExecutionAuthorizationService(JdbcTemplate jdbc, JsonColumnMapper json, WorkloadAuthorizationProperties properties,
                                             WorkloadAssertionSigner signer, WorkloadTlsIdentity tls, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.signer = signer;
        this.tls = tls;
        this.clock = clock;
    }

    /** 在领取事务中签发或轮换授权，失败使新执行与授权记录一起回滚。 */
    public WorkloadAssertion issue(ClaimedTaskView task) {
        WorkloadResource resource = WorkloadResource.forTask(task.taskName());
        if (resource == null) {
            return null;
        }
        requireEnabled();
        requireTransaction();
        if (!resource.taskName().equals(task.taskName()) || task.parentTaskInstanceId() != null
                || task.steps().isEmpty() || task.steps().size() > 8
                || task.steps().stream().noneMatch(step -> step.stepKind() == StepKind.NORMAL)
                || task.steps().stream().anyMatch(step -> !resource.taskName().equals(step.scriptPackage())
                || !"1.0.0".equals(step.scriptVersion()) || step.maxAttempts() != 1
                || step.scriptReleaseDigest() == null || !step.scriptReleaseDigest().matches("[a-f0-9]{64}"))) {
            throw unavailable();
        }
        Map<String, Object> state = lock(task.executionId());
        var identity = tls.executor(text(state, "node_name"));
        requireActive(state, task.leaseToken());
        if (!task.taskInstanceId().toString().equals(state.get("task_instance_id"))
                || task.fencingToken() != number(state, "fencing_token")) {
            throw lost();
        }
        String sha;
        try {
            sha = resource.parametersSha256(json.read(text(state, "parameters_json")));
            if (!sha.equals(resource.parametersSha256(task.parameters()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
        return rotate(state, resource, sha, identity);
    }

    /** 受保护任务的心跳不能复活过期租约，取消后也不续期授权；普通任务返回 null 沿用旧协议。 */
    public LeaseHeartbeatView renewIfProtected(UUID executionId, LeaseHeartbeatRequest request) {
        var known = one("""
                SELECT ti.task_name FROM task_execution te JOIN task_instance ti ON ti.id = te.task_instance_id WHERE te.id = ?
                """, executionId.toString());
        if (known == null || WorkloadResource.forTask(text(known, "task_name")) == null) {
            return null;
        }
        requireEnabled();
        requireTransaction();
        Map<String, Object> state = lock(executionId);
        var identity = tls.executor(text(state, "node_name"));
        if (!request.leaseToken().toString().equals(state.get("lease_token"))) {
            throw lost();
        }
        if (!"RUNNING".equals(state.get("task_status")) || !"RUNNING".equals(state.get("execution_status"))) {
            revokeExecution(executionId);
            return new LeaseHeartbeatView(instant(state, "lease_until"), true, "REVOKED");
        }
        requireActive(state, request.leaseToken());
        Map<String, Object> authorization = one("SELECT * FROM task_execution_authorization WHERE execution_id = ? FOR UPDATE", executionId.toString());
        requireIdentity(authorization, identity, text(state, "node_instance_id"));
        if (authorization.get("revoked_at") != null || !instant(authorization, "lease_expires_at").isAfter(clock.instant())) {
            throw lost();
        }
        Instant now = now();
        Instant deadline = instant(state, "task_started_at").plusSeconds(number(state, "timeout_seconds"));
        Instant lease = earlier(now.plusSeconds(Math.min(120, request.leaseSeconds())), deadline);
        jdbc.update("UPDATE task_execution SET lease_until = ?, last_heartbeat_at = ?, updated_at = ? WHERE id = ?",
                timestamp(lease), timestamp(now), timestamp(now), executionId.toString());
        state.put("lease_until", timestamp(lease));
        WorkloadResource resource = WorkloadResource.forTask(text(state, "task_name"));
        String sha = resource.parametersSha256(json.read(text(state, "parameters_json")));
        if (!sha.equals(authorization.get("task_parameters_sha256"))) {
            throw lost();
        }
        WorkloadAssertion assertion = rotate(state, resource, sha, identity);
        return new LeaseHeartbeatView(lease, false, "ACTIVE", assertion);
    }

    /** 在取消或任务级终结事务中立即撤销所有 generation，禁止五秒重叠继续授权。 */
    public void revokeTask(UUID taskId) {
        requireTransaction();
        var task = one("SELECT task_name FROM task_instance WHERE id = ?", taskId.toString());
        if (task != null && WorkloadResource.forTask(text(task, "task_name")) != null) {
            jdbc.update("""
                    UPDATE task_execution_authorization SET revoked_at = COALESCE(revoked_at, ?),
                        previous_jti_sha256 = NULL, previous_generation = NULL, previous_valid_until = NULL,
                        previous_assertion_expires_at = NULL, updated_at = ? WHERE task_instance_id = ?
                    """, timestamp(now()), timestamp(now()), taskId.toString());
        }
    }

    /** 在失租或执行终结事务中立即撤销该 execution 的全部授权。 */
    public void revokeExecution(UUID executionId) {
        requireTransaction();
        var state = one("""
                SELECT ti.task_name FROM task_execution te JOIN task_instance ti ON ti.id = te.task_instance_id WHERE te.id = ?
                """, executionId.toString());
        if (state != null && WorkloadResource.forTask(text(state, "task_name")) != null) {
            jdbc.update("""
                    UPDATE task_execution_authorization SET revoked_at = COALESCE(revoked_at, ?),
                        previous_jti_sha256 = NULL, previous_generation = NULL, previous_valid_until = NULL,
                        previous_assertion_expires_at = NULL, updated_at = ? WHERE execution_id = ?
                    """, timestamp(now()), timestamp(now()), executionId.toString());
        }
    }

    /** 后台回收与完成在修改 execution 前取得相同锁顺序，避免和受保护心跳反向持锁。 */
    public void lockMutationIfProtected(UUID executionId) {
        requireTransaction();
        var state = one("""
                SELECT ti.task_name FROM task_execution te JOIN task_instance ti ON ti.id = te.task_instance_id WHERE te.id = ?
                """, executionId.toString());
        if (state != null && WorkloadResource.forTask(text(state, "task_name")) != null) {
            lock(executionId);
        }
    }

    /** 每次在线检查都验证权威执行、当前节点实例和取消状态，不缓存 active 结果。 */
    public WorkloadIntrospection.Response introspect(WorkloadIntrospection.Request request) {
        requireEnabled();
        tls.reader();
        var row = one("""
                SELECT a.*, te.status AS execution_status, te.lease_until AS actual_lease_until,
                    te.fencing_token AS actual_fence, ti.status AS task_status, ti.cancel_requested_at,
                    ti.started_at AS task_started_at, td.timeout_seconds, en.instance_id AS current_node_instance,
                    en.enabled AS node_enabled
                FROM task_execution_authorization a JOIN task_execution te ON te.id = a.execution_id
                JOIN task_instance ti ON ti.id = a.task_instance_id JOIN task_definition td ON td.id = ti.task_definition_id
                JOIN executor_node en ON en.id = te.node_id WHERE a.execution_id = ? AND a.task_instance_id = ?
                    AND NOT EXISTS (SELECT 1 FROM task_execution newer WHERE newer.task_instance_id = a.task_instance_id
                        AND newer.fencing_token > a.fencing_token)
                """, request.executionId().toString(), request.taskInstanceId().toString());
        Instant now = clock.instant();
        if (row == null || row.get("revoked_at") != null || row.get("cancel_requested_at") != null
                || !"RUNNING".equals(row.get("execution_status")) || !"RUNNING".equals(row.get("task_status"))
                || !Boolean.TRUE.equals(row.get("node_enabled")) || !row.get("node_instance_id").equals(row.get("current_node_instance"))
                || request.fencingToken() != number(row, "fencing_token") || request.fencingToken() != number(row, "actual_fence")
                || !request.audience().equals(row.get("audience")) || !request.resourceType().equals(row.get("resource_type"))
                || !request.taskParametersSha256().equals(row.get("task_parameters_sha256"))
                || !request.cnfThumbprint().equals(binaryText(row, "cnf_thumbprint"))) {
            return inactive();
        }
        String sha = WorkloadResource.sha256(request.jti().toString());
        Instant until = earlier(instant(row, "lease_expires_at"), instant(row, "actual_lease_until"));
        until = earlier(until, instant(row, "task_started_at").plusSeconds(number(row, "timeout_seconds")));
        if (request.assertionGeneration() == number(row, "assertion_generation") && sha.equals(row.get("current_jti_sha256"))) {
            until = earlier(until, instant(row, "assertion_expires_at"));
        } else if (row.get("previous_generation") != null && request.assertionGeneration() == number(row, "previous_generation")
                && sha.equals(row.get("previous_jti_sha256"))) {
            until = earlier(until, earlier(instant(row, "previous_valid_until"), instant(row, "previous_assertion_expires_at")));
        } else {
            return inactive();
        }
        return until.isAfter(now) ? new WorkloadIntrospection.Response(true, until) : inactive();
    }

    /** 公钥读取同样限制为 Reader 工作负载，不暴露部署路径或私钥。 */
    public Map<String, Object> jwks() {
        requireEnabled();
        tls.reader();
        return signer.jwks();
    }

    private WorkloadAssertion rotate(Map<String, Object> state, WorkloadResource resource, String parameterSha,
                                      WorkloadTlsIdentity.Identity identity) {
        String execution = text(state, "id");
        Map<String, Object> current = one("SELECT * FROM task_execution_authorization WHERE execution_id = ? FOR UPDATE", execution);
        if (current != null) {
            requireIdentity(current, identity, text(state, "node_instance_id"));
            if (current.get("revoked_at") != null || number(current, "fencing_token") != number(state, "fencing_token")
                    || !parameterSha.equals(current.get("task_parameters_sha256"))
                    || !instant(current, "lease_expires_at").isAfter(clock.instant())) {
                throw lost();
            }
        }
        Instant now = now();
        Instant lease = instant(state, "lease_until");
        Instant expires = earlier(lease, earlier(now.plusSeconds(properties.assertionSeconds()),
                instant(state, "task_started_at").plusSeconds(number(state, "timeout_seconds")))).truncatedTo(ChronoUnit.SECONDS);
        if (!expires.isAfter(now)) {
            throw lost();
        }
        long generation = current == null ? 1 : Math.addExact(number(current, "assertion_generation"), 1);
        UUID jti = UUID.randomUUID();
        String jtiSha = WorkloadResource.sha256(jti.toString());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.issuer());
        claims.put("aud", resource.audience());
        claims.put("resourceType", resource.resourceType());
        claims.put("taskInstanceId", state.get("task_instance_id"));
        claims.put("packageName", resource.taskName());
        claims.put("packageVersion", "1.0.0");
        claims.put("taskParametersSha256", parameterSha);
        claims.put("executionId", execution);
        claims.put("fencingToken", number(state, "fencing_token"));
        claims.put("leaseExpiresAt", lease.getEpochSecond());
        claims.put("assertionGeneration", generation);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expires.getEpochSecond());
        claims.put("jti", jti.toString());
        claims.put("cnf", Map.of("x5t#S256", identity.thumbprint()));
        String token = signer.sign(claims);
        if (current == null) {
            // 换 execution/fence 没有旧令牌重叠；撤销和新授权插入与执行创建一起提交。
            revokeTask(UUID.fromString(text(state, "task_instance_id")));
            jdbc.update("""
                    INSERT INTO task_execution_authorization (execution_id, task_instance_id, fencing_token, audience, resource_type,
                        package_name, package_version, task_parameters_sha256, workload_identity, node_instance_id, cnf_thumbprint,
                        assertion_generation, current_jti_sha256, lease_expires_at, assertion_expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, '1.0.0', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, execution, state.get("task_instance_id"), number(state, "fencing_token"), resource.audience(), resource.resourceType(),
                    resource.taskName(), parameterSha, binary(identity.uri()), state.get("node_instance_id"), binary(identity.thumbprint()),
                    generation, jtiSha, timestamp(lease), timestamp(expires), timestamp(now), timestamp(now));
        } else {
            Instant previousUntil = earlier(now.plusSeconds(properties.overlapSeconds()), instant(current, "assertion_expires_at"));
            jdbc.update("""
                    UPDATE task_execution_authorization SET previous_jti_sha256 = current_jti_sha256,
                        previous_generation = assertion_generation, previous_assertion_expires_at = assertion_expires_at,
                        previous_valid_until = ?, assertion_generation = ?, current_jti_sha256 = ?,
                        lease_expires_at = ?, assertion_expires_at = ?, updated_at = ? WHERE execution_id = ?
                    """, timestamp(previousUntil), generation, jtiSha, timestamp(lease), timestamp(expires), timestamp(now), execution);
        }
        return new WorkloadAssertion(token, expires);
    }

    private Map<String, Object> lock(UUID executionId) {
        Map<String, Object> known = one("SELECT task_instance_id, node_id FROM task_execution WHERE id = ?", executionId.toString());
        if (known == null) {
            throw lost();
        }
        // 与领取共用 node → task → execution 顺序，并使用锁定行的当前值，避免等待后仍读取旧取消快照。
        var node = one("SELECT name, instance_id, enabled FROM executor_node WHERE id = ? FOR UPDATE", known.get("node_id"));
        var task = one("SELECT * FROM task_instance WHERE id = ? FOR UPDATE", known.get("task_instance_id"));
        if (node == null || task == null) {
            throw lost();
        }
        var execution = one("SELECT * FROM task_execution WHERE id = ? FOR UPDATE", executionId.toString());
        var definition = one("SELECT timeout_seconds FROM task_definition WHERE id = ?", task.get("task_definition_id"));
        if (execution == null || definition == null) {
            throw lost();
        }
        execution.put("execution_status", execution.get("status"));
        execution.put("task_name", task.get("task_name"));
        execution.put("parameters_json", task.get("parameters_json"));
        execution.put("task_status", task.get("status"));
        execution.put("cancel_requested_at", task.get("cancel_requested_at"));
        execution.put("task_started_at", task.get("started_at"));
        execution.put("timeout_seconds", definition.get("timeout_seconds"));
        execution.put("node_name", node.get("name"));
        execution.put("node_instance_id", node.get("instance_id"));
        execution.put("node_enabled", node.get("enabled"));
        return execution;
    }

    private void requireActive(Map<String, Object> state, UUID leaseToken) {
        if (!leaseToken.toString().equals(state.get("lease_token")) || !"RUNNING".equals(state.get("execution_status"))
                || !"RUNNING".equals(state.get("task_status")) || state.get("cancel_requested_at") != null
                || !Boolean.TRUE.equals(state.get("node_enabled")) || !instant(state, "lease_until").isAfter(clock.instant())
                || !instant(state, "task_started_at").plusSeconds(number(state, "timeout_seconds")).isAfter(clock.instant())) {
            throw lost();
        }
        Long newest = jdbc.queryForObject("SELECT MAX(fencing_token) FROM task_execution WHERE task_instance_id = ?",
                Long.class, state.get("task_instance_id"));
        if (newest == null || newest != number(state, "fencing_token")) {
            throw lost();
        }
    }

    private static void requireIdentity(Map<String, Object> row, WorkloadTlsIdentity.Identity identity, String nodeInstance) {
        if (row == null || !identity.uri().equals(binaryText(row, "workload_identity"))
                || !identity.thumbprint().equals(binaryText(row, "cnf_thumbprint")) || !nodeInstance.equals(row.get("node_instance_id"))) {
            throw lost();
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw unavailable();
        }
    }

    private Map<String, Object> one(String sql, Object... parameters) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, parameters);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workload authorization requires a lease transaction");
        }
    }

    private static WorkloadIntrospection.Response inactive() {
        return new WorkloadIntrospection.Response(false, null);
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }

    private static String binaryText(Map<String, Object> row, String key) {
        return new String((byte[]) row.get(key), StandardCharsets.UTF_8);
    }

    private static byte[] binary(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static Instant instant(Map<String, Object> row, String key) {
        return ((Timestamp) row.get(key)).toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static SchedulerException unavailable() {
        return new SchedulerException(ErrorCode.WORKLOAD_AUTHORIZATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Workload authorization is unavailable");
    }

    private static SchedulerException lost() {
        return new SchedulerException(ErrorCode.EXECUTION_LEASE_LOST, HttpStatus.CONFLICT, "Workload execution is no longer current");
    }
}
