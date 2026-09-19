package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 授权传输夹具，不代表 Scheduler 签发或 Reader 验签的集成证据。 */
public final class WorkloadFixtures {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private WorkloadFixtures() { }

    /** 创建执行契约摘要有效的受保护任务。 */
    public static ClaimedTask task(Instant now, String thumbprint) throws Exception {
        String name = "reader_adapt_novel_chapter";
        UUID definition = UUID.randomUUID();
        var step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", name, "1.0.0", "a".repeat(64),
                "main.py", List.of(), 900, "FAIL_TASK", 1, 1);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : List.of(definition.toString(), "1", name, step.stepDefinitionId().toString(), "run",
                "NORMAL", name, "1.0.0", "a".repeat(64), "main.py", "900", "FAIL_TASK", "1", "1")) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        var task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, name, definition, 1,
                HexFormat.of().formatHex(digest.digest()), UUID.randomUUID(), 1, now.plusSeconds(90),
                now.plusSeconds(900), false, Map.of("adaptationId", UUID.randomUUID().toString()), List.of(step));
        return withToken(task, token(task, now, task.leaseUntil(), thumbprint, 1, Map.of()), now.plusSeconds(60));
    }

    /** 复制任务并设置只读传输态凭据。 */
    public static ClaimedTask withToken(ClaimedTask task, String token, Instant expiresAt) {
        return new ClaimedTask(task.executionId(), task.taskInstanceId(), task.parentTaskInstanceId(), task.taskName(),
                task.definitionId(), task.definitionVersion(), task.definitionDigest(), task.leaseToken(),
                task.fencingToken(), task.leaseUntil(), task.deadlineAt(), task.mayCreateChildren(), task.parameters(),
                task.steps(), token, expiresAt);
    }

    /** 构造字段受控的传输 token，签名占位符只用于宿主范围校验。 */
    public static String token(ClaimedTask task, Instant now, Instant lease, String thumbprint, long generation,
                               Map<String, Object> changes) throws Exception {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", "mytools-task-scheduler");
        claims.put("aud", "reader-adaptation-internal");
        claims.put("resourceType", "CHAPTER_ADAPTATION");
        claims.put("taskInstanceId", task.taskInstanceId().toString());
        claims.put("packageName", task.taskName());
        claims.put("packageVersion", "1.0.0");
        claims.put("executionId", task.executionId().toString());
        claims.put("fencingToken", task.fencingToken());
        claims.put("leaseExpiresAt", lease.getEpochSecond());
        claims.put("assertionGeneration", generation);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", Math.min(now.plusSeconds(60).getEpochSecond(),
                Math.min(lease.getEpochSecond(), task.deadlineAt().getEpochSecond())));
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("cnf", Map.of("x5t#S256", thumbprint));
        claims.put("taskParametersSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(MAPPER.writeValueAsBytes(task.parameters()))));
        claims.putAll(changes);
        var base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString(MAPPER.writeValueAsBytes(Map.of("alg", "Ed25519", "typ", "mytools-workload+jwt", "kid", "fixture")))
                + "." + base64.encodeToString(MAPPER.writeValueAsBytes(claims)) + "." + base64.encodeToString(new byte[64]);
    }

    /** 模拟 Scheduler 仅在 HTTP 投影中添加授权的 JSON。 */
    public static String transport(ClaimedTask task) throws Exception {
        var result = MAPPER.valueToTree(task);
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("workloadAssertion", task.workloadAssertion());
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("workloadAssertionExpiresAt", task.workloadAssertionExpiresAt().toString());
        return MAPPER.writeValueAsString(result);
    }
}
