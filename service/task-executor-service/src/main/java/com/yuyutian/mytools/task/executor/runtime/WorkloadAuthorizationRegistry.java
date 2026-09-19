package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionLease;
import com.yuyutian.mytools.task.executor.client.ExecutorWorkloadTls;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主内存中的短期授权，续期只替换当前执行，不持久化且不允许关闭后复活。
 * 此处校验可信 Scheduler TLS 传输的范围一致性，不代替 Reader 验签和逐请求在线撤销检查。
 */
@Component
public final class WorkloadAuthorizationRegistry {
    private static final Map<String, Scope> SCOPES = Map.of(
            "reader_adapt_novel_chapter", new Scope("adaptationId", "reader-adaptation-internal", "CHAPTER_ADAPTATION"),
            "reader_project_ebook_text", new Scope("bindingId", "reader-ebook-projection-internal", "EBOOK_BINDING"),
            "reader_probe_novel_adaptation_provider", new Scope("probeId", "reader-provider-probe-internal", "PROVIDER_PROBE"));
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "resourceType", "taskInstanceId", "packageName",
            "packageVersion", "taskParametersSha256", "executionId", "fencingToken", "leaseExpiresAt",
            "assertionGeneration", "iat", "exp", "jti", "cnf");
    private final Map<UUID, Grant> grants = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String certificateThumbprint;

    /** 注入真实宿主证书指纹，不从任务或 Header 接受证书身份。 */
    @Autowired
    public WorkloadAuthorizationRegistry(ObjectMapper mapper, ExecutorWorkloadTls tls) {
        this(mapper, Clock.systemUTC(), tls.thumbprint());
    }

    WorkloadAuthorizationRegistry(ObjectMapper mapper, Clock clock, String certificateThumbprint) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = clock;
        this.certificateThumbprint = certificateThumbprint;
    }

    /** 识别受保护任务，包括不允许降级成普通任务的大小写变体。 */
    public static boolean protectedTask(String name) {
        return name != null && SCOPES.keySet().stream().anyMatch(value -> value.equalsIgnoreCase(name));
    }

    /** 领取完成后登记授权；普通任务拒绝附带不相关授权。 */
    public synchronized void acceptClaim(ClaimedTask task) throws IOException {
        if (!protectedTask(task.taskName())) {
            requireAbsent(task.workloadAssertion(), task.workloadAssertionExpiresAt());
            return;
        }
        Grant grant = parse(task, task.workloadAssertion(), task.workloadAssertionExpiresAt(), task.leaseUntil());
        // 仅清理已达业务截止时间的对象，短 token 到期后仍可能在有效租约内收到续期。
        grants.entrySet().removeIf(entry -> !entry.getValue().deadlineAt().isAfter(clock.instant()));
        if (grants.size() >= 256 && !grants.containsKey(task.executionId())) throw unavailable();
        Grant previous = grants.get(task.executionId());
        if (previous != null && grant.generation() <= previous.generation()) throw unavailable();
        grants.put(task.executionId(), grant);
    }

    /** 续期仅更新已领取且仍打开的执行，取消及异常授权立即关闭当前普通能力。 */
    public synchronized void acceptHeartbeat(ClaimedTask task, ExecutionLease lease) throws IOException {
        if (!protectedTask(task.taskName())) {
            requireAbsent(lease.workloadAssertion(), lease.workloadAssertionExpiresAt());
            return;
        }
        if (lease.cancelRequested() || !"ACTIVE".equals(lease.leaseState())) {
            close(task.executionId());
            return;
        }
        Grant previous = grants.get(task.executionId());
        if (previous == null) throw unavailable();
        try {
            Grant next = parse(task, lease.workloadAssertion(), lease.workloadAssertionExpiresAt(), lease.leaseUntil());
            if (next.generation() <= previous.generation()) {
                // 在途旧心跳只能被忽略，不能覆盖当前已更新的授权。
                return;
            }
            grants.put(task.executionId(), next);
        } catch (IOException exception) {
            close(task.executionId());
            throw exception;
        }
    }

    /** 每次普通 broker 调用前重新取得当前授权，过期时不返回旧 token。 */
    public synchronized Grant current(UUID executionId) throws IOException {
        Grant result = grants.get(executionId);
        if (result == null || !result.expiresAt().isAfter(clock.instant())) throw unavailable();
        return result;
    }

    /** 终态、取消或租约丢失时关闭执行，迟到 heartbeat 不得重新登记。 */
    public synchronized void close(UUID executionId) { grants.remove(executionId); }

    private Grant parse(ClaimedTask task, String token, Instant expiresAt, Instant leaseUntil) throws IOException {
        try {
            Scope scope = SCOPES.get(task.taskName());
            if (scope == null || certificateThumbprint == null || token == null || token.length() > 8192
                    || expiresAt == null || leaseUntil == null || task.deadlineAt() == null
                    || task.parentTaskInstanceId() != null || task.mayCreateChildren() || task.fencingToken() < 1
                    || task.definitionDigest() == null || !task.definitionDigest().matches("[a-f0-9]{64}")
                    || task.steps() == null || task.steps().isEmpty() || task.steps().size() > 8
                    || task.parameters() == null || !task.parameters().keySet().equals(Set.of(scope.parameter()))) {
                throw unavailable();
            }
            for (var step : task.steps()) {
                if (!task.taskName().equals(step.scriptPackage()) || !"1.0.0".equals(step.scriptVersion())
                        || step.maxAttempts() != 1 || step.scriptReleaseDigest() == null
                        || !step.scriptReleaseDigest().matches("[a-f0-9]{64}")) throw unavailable();
            }
            Object resource = task.parameters().get(scope.parameter());
            if (!(resource instanceof String id) || !UUID.fromString(id).toString().equals(id)) throw unavailable();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw unavailable();
            JsonNode header = mapper.readTree(decode(parts[0], 512));
            JsonNode claims = mapper.readTree(decode(parts[1], 4096));
            if (decode(parts[2], 64).length != 64 || header == null || !header.isObject() || header.size() != 3
                    || !"Ed25519".equals(string(header, "alg"))
                    || !"mytools-workload+jwt".equals(string(header, "typ"))
                    || !string(header, "kid").matches("[A-Za-z0-9_.-]{1,64}")
                    || claims == null || !claims.isObject() || claims.size() != CLAIMS.size()) throw unavailable();
            for (String field : CLAIMS) if (!claims.has(field)) throw unavailable();
            long issued = number(claims, "iat");
            long expiration = number(claims, "exp");
            long generation = number(claims, "assertionGeneration");
            Instant now = clock.instant();
            String parameters = "{\"" + scope.parameter() + "\":\"" + resource + "\"}";
            String parametersHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(parameters.getBytes(StandardCharsets.UTF_8)));
            JsonNode cnf = claims.get("cnf");
            if (!task.executionId().toString().equals(string(claims, "executionId"))
                    || !task.taskInstanceId().toString().equals(string(claims, "taskInstanceId"))
                    || task.fencingToken() != number(claims, "fencingToken")
                    || !scope.audience().equals(string(claims, "aud"))
                    || !scope.resourceType().equals(string(claims, "resourceType"))
                    || !task.taskName().equals(string(claims, "packageName"))
                    || !"1.0.0".equals(string(claims, "packageVersion"))
                    || !parametersHash.equals(string(claims, "taskParametersSha256"))
                    || string(claims, "iss").isBlank() || string(claims, "iss").length() > 128
                    || cnf == null || !cnf.isObject() || cnf.size() != 1
                    || !certificateThumbprint.equals(string(cnf, "x5t#S256"))
                    || generation < 1 || issued < 0 || issued > now.getEpochSecond() + 2
                    || expiration <= issued || expiration - issued > 120
                    || !Instant.ofEpochSecond(expiration).equals(expiresAt) || !expiresAt.isAfter(now)
                    || expiresAt.isAfter(leaseUntil) || expiresAt.isAfter(task.deadlineAt())
                    || number(claims, "leaseExpiresAt") != leaseUntil.truncatedTo(ChronoUnit.SECONDS).getEpochSecond()
                    || !UUID.fromString(string(claims, "jti")).toString().equals(string(claims, "jti"))) {
                throw unavailable();
            }
            return new Grant(token, expiresAt, generation, task.deadlineAt());
        } catch (Exception exception) {
            // 上游响应或 token 的任何解析失败只产生固定诊断，不能携带原始输入。
            throw unavailable();
        }
    }

    private static byte[] decode(String value, int maximum) throws IOException {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw unavailable();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (decoded.length > maximum || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
            throw unavailable();
        }
        return decoded;
    }

    private static String string(JsonNode value, String field) throws IOException {
        if (!value.path(field).isTextual()) throw unavailable();
        return value.get(field).textValue();
    }

    private static long number(JsonNode value, String field) throws IOException {
        if (!value.path(field).isIntegralNumber() || !value.get(field).canConvertToLong()) throw unavailable();
        return value.get(field).longValue();
    }

    private static void requireAbsent(String token, Instant expiresAt) throws IOException {
        if (token != null || expiresAt != null) throw unavailable();
    }

    private static IOException unavailable() { return new IOException("Executor workload authorization is unavailable"); }

    private record Scope(String parameter, String audience, String resourceType) { }

    /** 只用于宿主 broker，不允许通用 JSON 及诊断持久化。 */
    @JsonIgnoreType
    public record Grant(@JsonIgnore String token, Instant expiresAt, long generation, Instant deadlineAt) {
        /** 不输出 token。 */
        @Override
        public String toString() { return "Grant[expiresAt=" + expiresAt + ", generation=" + generation + ", token=REDACTED]"; }
    }
}
