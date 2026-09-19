package com.yuyutian.mytools.task.scheduler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.config.WorkloadAuthorizationProperties;
import com.yuyutian.mytools.task.scheduler.model.*;
import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实租约、签名与授权事务；仅用内存密钥和容器证书属性夹具，不代表部署 mTLS 已验收。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workload_authorization;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.flyway.target=140", "task.security.required=true",
        "task.security.business-clients.reader-service=fixture-reader-token",
        "task.workload-authorization.enabled=true", "task.workload-authorization.signing-keyring-file=/unused-fixture-keyring",
        "task.workload-authorization.executor-identities.workload-fixture-node=spiffe://fixture.test/executor",
        "task.workload-authorization.reader-identities=spiffe://fixture.test/reader",
        "task.scheduler.lease-recovery-delay-ms=3600000", "task.scheduler.deadline-scan-delay-ms=3600000"})
@AutoConfigureMockMvc
class TaskExecutionAuthorizationServiceTest {
    private static final String TASK = "reader_adapt_novel_chapter";
    private static final String SHA = "a".repeat(64);
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private TaskDefinitionService definitions;
    @Autowired private TaskStepService steps;
    @Autowired private ExecutionTopologyService topology;
    @Autowired private ReaderAdaptationDispatchService readerDispatch;
    @Autowired private TaskDispatchService dispatch;
    @Autowired private TaskExecutionAuthorizationService authorization;
    @Autowired private TaskLeaseRecoveryService recovery;
    @Autowired private WorkloadTlsIdentity tls;
    @Autowired private WorkloadAuthorizationProperties properties;
    @Autowired private JsonColumnMapper json;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MockMvc mvc;
    @MockBean private WorkloadAssertionSigner signer;
    private KeyPair key;
    private UUID adaptation;
    private UUID task;
    private UUID node;
    private UUID instance;

    @BeforeEach
    void setup() throws Exception {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'task_execution_authorization'", Integer.class) == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V145__add_reader_adaptation_dispatch_guard.sql"),
                    new ClassPathResource("db/migration/V146__add_task_execution_authorization.sql"))
                    .execute(Objects.requireNonNull(jdbc.getDataSource()));
        }
        // 本测试库独占，关闭前例的任务避免调度拾取旧夹具，不清除任何生产或工作区数据。
        jdbc.update("UPDATE task_instance SET status = 'CANCELLED' WHERE status IN ('QUEUED', 'RUNNING', 'CANCELLING')");
        jdbc.update("UPDATE task_execution SET status = 'CANCELLED' WHERE status = 'RUNNING'");
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var realSigner = new WorkloadAssertionSigner("fixture-key", Map.of("fixture-key", key), mapper);
        when(signer.sign(anyMap())).thenAnswer(invocation -> realSigner.sign(invocation.getArgument(0)));
        when(signer.jwks()).thenAnswer(invocation -> realSigner.jwks());
        readerIdentity();
        UUID cluster;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM task_definition WHERE name = ?", Integer.class, TASK) == 0) {
            cluster = topology.createCluster(new CreateExecutionClusterRequest("workload-fixture-cluster", "Fixture workload cluster",
                    "LEAST_RUNNING", 2, Map.of(), true)).id();
            var definition = definitions.create(new CreateTaskDefinitionRequest(TASK, "Fixture workload task", TaskType.IMMEDIATE,
                    900, cluster, null, null, ExecutionMode.SINGLE_NODE, true, 2, "SKIP", "IGNORE",
                    Map.of("type", "object", "required", List.of("adaptationId"), "additionalProperties", false,
                            "properties", Map.of("adaptationId", Map.of("type", "string"))), Map.of()));
            steps.create(definition.id(), new CreateTaskStepRequest("adapt", "Fixture step", StepKind.NORMAL, TASK, "1.0.0", "main.py",
                    List.of(), true, 900, FailurePolicy.FAIL_TASK, 10, 1));
        }
        jdbc.update("UPDATE task_step_definition SET max_attempts = 1, script_version = '1.0.0' WHERE script_package = ?", TASK);
        instance = UUID.randomUUID();
        node = topology.registerNode(new RegisterExecutorNodeRequest("workload-fixture-node", instance.toString(),
                Map.of("scriptReleases", Map.of(TASK + ":1.0.0", SHA)), Map.of("reader.adaptation", "enabled"), 2,
                Set.of("workload-fixture-cluster"))).id();
        adaptation = UUID.randomUUID();
        task = readerDispatch.submit(adaptation).taskInstanceId();
    }

    @AfterEach
    void clearIdentity() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldAtomicallySignOpaqueTaskContractAndExposeOnlyTransportProjection() throws Exception {
        var claimed = claim();
        var token = claimed.workloadAuthorization().token();
        JsonNode payload = verify(token);
        assertThat(payload.size()).isEqualTo(15);
        assertThat(payload.get("aud").textValue()).isEqualTo("reader-adaptation-internal");
        assertThat(payload.get("taskInstanceId").textValue()).isEqualTo(task.toString());
        assertThat(payload.get("taskParametersSha256").textValue()).isEqualTo(WorkloadResource.ADAPTATION.parametersSha256(Map.of("adaptationId", adaptation.toString())));
        assertThat(payload.has("ownerId")).isFalse();
        assertThat(payload.has("providerDeploymentId")).isFalse();
        assertThat(mapper.writeValueAsString(claimed)).doesNotContain(token, "workloadAuthorization");
        assertThat(mapper.writeValueAsString(new WorkloadTransport.Claim(claimed))).contains(token, "workloadAssertion")
                .doesNotContain("workloadAuthorization");
        assertThat(claimed.toString()).doesNotContain(token, claimed.leaseToken().toString());
        assertThat(row(claimed).toString()).doesNotContain(token, payload.get("jti").textValue());
        readerIdentity();
        assertThat(authorization.introspect(query(payload)).active()).isTrue();
        assertThat(mapper.writeValueAsString(authorization.jwks())).doesNotContain("private", "PKCS8", "\"d\"");
    }

    @Test
    void shouldRollbackExecutionWhenTlsIdentityMissingOrSigningFails() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        expect(ErrorCode.UNAUTHORIZED, () -> dispatch.claim(request()));
        assertThat(executionCount()).isZero();
        assertThat(taskStatus()).isEqualTo("QUEUED");
        executorIdentity();
        doThrow(new IllegalStateException("Fixture signing failure")).when(signer).sign(anyMap());
        assertThatThrownBy(() -> dispatch.claim(request())).isInstanceOf(IllegalStateException.class);
        assertThat(executionCount()).isZero();
        assertThat(taskStatus()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_execution_authorization WHERE task_instance_id = ?", Integer.class, task.toString())).isZero();
    }

    @Test
    void shouldRotateHeartbeatAndLimitPreviousGenerationToFiveSeconds() throws Exception {
        var claimed = claim();
        var original = query(verify(claimed.workloadAuthorization().token()));
        executorIdentity();
        var heartbeat = dispatch.heartbeat(claimed.executionId(), new LeaseHeartbeatRequest(claimed.leaseToken(), 120));
        var replacement = query(verify(heartbeat.workloadAuthorization().token()));
        assertThat(replacement.assertionGeneration()).isEqualTo(original.assertionGeneration() + 1);
        assertThat(mapper.writeValueAsString(heartbeat)).doesNotContain(heartbeat.workloadAuthorization().token());
        readerIdentity();
        assertThat(authorization.introspect(original).active()).isTrue();
        assertThat(authorization.introspect(replacement).active()).isTrue();
        jdbc.update("UPDATE task_execution_authorization SET previous_valid_until = ? WHERE execution_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), claimed.executionId().toString());
        assertThat(authorization.introspect(original).active()).isFalse();
        assertThat(authorization.introspect(replacement).active()).isTrue();
    }

    @Test
    void shouldRevokeBothGenerationsInCancellationTransaction() throws Exception {
        var claimed = claim();
        var original = query(verify(claimed.workloadAuthorization().token()));
        var renewed = dispatch.heartbeat(claimed.executionId(), new LeaseHeartbeatRequest(claimed.leaseToken(), 120));
        var current = query(verify(renewed.workloadAuthorization().token()));
        readerIdentity();
        readerDispatch.cancel(adaptation);
        assertThat(authorization.introspect(original).active()).isFalse();
        assertThat(authorization.introspect(current).active()).isFalse();
        assertThat(row(claimed).get("revoked_at")).isNotNull();
        assertThat(row(claimed).get("previous_jti_sha256")).isNull();
        var lease = jdbc.queryForObject("SELECT lease_until FROM task_execution WHERE id = ?", Timestamp.class, claimed.executionId().toString());
        executorIdentity();
        var stopped = dispatch.heartbeat(claimed.executionId(), new LeaseHeartbeatRequest(claimed.leaseToken(), 120));
        assertThat(stopped.cancelRequested()).isTrue();
        assertThat(stopped.workloadAuthorization()).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_until FROM task_execution WHERE id = ?", Timestamp.class, claimed.executionId().toString())).isEqualTo(lease);
    }

    @Test
    void shouldNotReviveExpiredLeaseBeforeRecoveryScannerRuns() throws Exception {
        var claimed = claim();
        var query = query(verify(claimed.workloadAuthorization().token()));
        jdbc.update("UPDATE task_execution SET lease_until = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), claimed.executionId().toString());
        expect(ErrorCode.EXECUTION_LEASE_LOST, () -> dispatch.heartbeat(claimed.executionId(), new LeaseHeartbeatRequest(claimed.leaseToken(), 120)));
        readerIdentity();
        assertThat(authorization.introspect(query).active()).isFalse();
        recovery.recoverExpiredLeases();
        assertThat(row(claimed).get("revoked_at")).isNotNull();
    }

    @Test
    void shouldRotateClaimReplayWithoutNewExecutionAndRejectDifferentCertificate() throws Exception {
        executorIdentity();
        var request = request();
        var first = dispatch.claim(request).orElseThrow();
        var replay = dispatch.claim(request).orElseThrow();
        assertThat(replay.executionId()).isEqualTo(first.executionId());
        assertThat(executionCount()).isEqualTo(1);
        assertThat(replay.workloadAuthorization().token()).isNotEqualTo(first.workloadAuthorization().token());
        installIdentity("spiffe://fixture.test/executor", "different-certificate", "task-executor-service");
        expect(ErrorCode.EXECUTION_LEASE_LOST, () -> dispatch.heartbeat(first.executionId(), new LeaseHeartbeatRequest(first.leaseToken(), 120)));
    }

    @Test
    void shouldRejectWrongAudienceFenceJtiAndNodeRestart() throws Exception {
        var claimed = claim();
        var valid = query(verify(claimed.workloadAuthorization().token()));
        readerIdentity();
        assertThat(authorization.introspect(new WorkloadIntrospection.Request(UUID.randomUUID(), task, claimed.executionId(),
                valid.fencingToken(), valid.assertionGeneration(), valid.cnfThumbprint(), valid.audience(), valid.resourceType(), valid.taskParametersSha256())).active()).isFalse();
        assertThat(authorization.introspect(new WorkloadIntrospection.Request(valid.jti(), task, claimed.executionId(),
                valid.fencingToken() + 1, valid.assertionGeneration(), valid.cnfThumbprint(), valid.audience(), valid.resourceType(), valid.taskParametersSha256())).active()).isFalse();
        assertThat(authorization.introspect(new WorkloadIntrospection.Request(valid.jti(), task, claimed.executionId(),
                valid.fencingToken(), valid.assertionGeneration(), valid.cnfThumbprint(), "reader-ebook-projection-internal", valid.resourceType(), valid.taskParametersSha256())).active()).isFalse();
        jdbc.update("UPDATE executor_node SET instance_id = ? WHERE id = ?", UUID.randomUUID().toString(), node.toString());
        assertThat(authorization.introspect(valid).active()).isFalse();
        executorIdentity();
        expect(ErrorCode.EXECUTION_LEASE_LOST, () -> dispatch.heartbeat(claimed.executionId(), new LeaseHeartbeatRequest(claimed.leaseToken(), 120)));
    }

    @Test
    void shouldRejectProviderStepRetryAndMissingImmutableReleaseDigest() throws Exception {
        jdbc.update("UPDATE task_step_definition SET max_attempts = 2 WHERE script_package = ?", TASK);
        executorIdentity();
        expect(ErrorCode.WORKLOAD_AUTHORIZATION_UNAVAILABLE, () -> dispatch.claim(request()));
        assertThat(executionCount()).isZero();
        jdbc.update("UPDATE task_step_definition SET max_attempts = 1 WHERE script_package = ?", TASK);
        jdbc.update("UPDATE executor_node SET capabilities_json = '{}' WHERE id = ?", node.toString());
        expect(ErrorCode.WORKLOAD_AUTHORIZATION_UNAVAILABLE, () -> dispatch.claim(request()));
        assertThat(executionCount()).isZero();
    }

    @Test
    void shouldRenewThroughoutNineHundredSecondTaskAndRefusePastDeadline() throws Exception {
        var claimed = claim();
        var clock = new MutableClock();
        var controlled = new TaskExecutionAuthorizationService(jdbc, json, properties, signer, tls, clock);
        var transaction = new TransactionTemplate(transactions);
        WorkloadIntrospection.Request last = null;
        for (int round = 0; round < 44; round++) {
            clock.now = clock.now.plusSeconds(20);
            executorIdentity();
            var renewal = transaction.execute(ignored -> controlled.renewIfProtected(claimed.executionId(), new LeaseHeartbeatRequest(claimed.leaseToken(), 120)));
            last = query(verify(Objects.requireNonNull(renewal).workloadAuthorization().token()));
            readerIdentity();
            assertThat(controlled.introspect(last).active()).isTrue();
        }
        assertThat(Objects.requireNonNull(last).assertionGeneration()).isEqualTo(45);
        clock.now = clock.now.plusSeconds(25);
        assertThat(controlled.introspect(last).active()).isFalse();
        executorIdentity();
        expect(ErrorCode.EXECUTION_LEASE_LOST, () -> transaction.execute(ignored -> controlled.renewIfProtected(claimed.executionId(),
                new LeaseHeartbeatRequest(claimed.leaseToken(), 120))));
    }

    @Test
    void shouldRevokeCompletionAndExcludeAuthorizationFromLegacyTransport() throws Exception {
        var claimed = claim();
        var valid = query(verify(claimed.workloadAuthorization().token()));
        dispatch.complete(claimed.executionId(), new CompleteExecutionRequest(claimed.leaseToken(), TaskStatus.SUCCEEDED));
        readerIdentity();
        assertThat(authorization.introspect(valid).active()).isFalse();
        assertThat(row(claimed).get("revoked_at")).isNotNull();
        assertThat(mapper.writeValueAsString(new WorkloadTransport.Claim(claimed.withWorkloadAuthorization(null))))
                .doesNotContain("workloadAssertion", "workloadAuthorization");
        assertThat(mapper.writeValueAsString(new WorkloadTransport.Heartbeat(new LeaseHeartbeatView(Instant.now(), false, "ACTIVE"))))
                .doesNotContain("workloadAssertion", "workloadAuthorization");
    }

    @Test
    void shouldIssueNewFenceWithoutAnyPreviousExecutionOverlap() throws Exception {
        var old = claim();
        var oldQuery = query(verify(old.workloadAuthorization().token()));
        jdbc.update("UPDATE task_execution SET lease_until = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), old.executionId().toString());
        recovery.recoverExpiredLeases();
        jdbc.update("UPDATE task_instance SET available_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), task.toString());
        var replacement = claim();
        assertThat(replacement.fencingToken()).isGreaterThan(old.fencingToken());
        readerIdentity();
        assertThat(authorization.introspect(oldQuery).active()).isFalse();
        assertThat(authorization.introspect(query(verify(replacement.workloadAuthorization().token()))).active()).isTrue();
        assertThat(row(old).get("previous_jti_sha256")).isNull();
        assertThat(row(old).get("revoked_at")).isNotNull();
    }

    @Test
    void shouldRejectHttpCertificateHeadersAndMalformedIntrospection() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        String base = "/api/internal/v1/task-execution-authorizations";
        mvc.perform(get(base + "/jwks").secure(true).header("X-Client-Cert", "untrusted")
                .header("X-Task-Business-Token", "fixture-reader-token")).andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/introspect").contentType("application/json").content("{\"ownerId\":41}"))
                .andExpect(status().isBadRequest());
    }

    private ClaimedTaskView claim() throws Exception {
        executorIdentity();
        return dispatch.claim(request()).orElseThrow();
    }

    private ClaimTaskRequest request() {
        return new ClaimTaskRequest(node, instance, UUID.randomUUID(), 120);
    }

    private JsonNode verify(String token) throws Exception {
        var parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        var header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").textValue()).isEqualTo("Ed25519");
        Signature verification = Signature.getInstance("Ed25519");
        verification.initVerify(key.getPublic());
        verification.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verification.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        return mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private WorkloadIntrospection.Request query(JsonNode payload) {
        return new WorkloadIntrospection.Request(UUID.fromString(payload.get("jti").textValue()), UUID.fromString(payload.get("taskInstanceId").textValue()),
                UUID.fromString(payload.get("executionId").textValue()), payload.get("fencingToken").longValue(), payload.get("assertionGeneration").longValue(),
                payload.get("cnf").get("x5t#S256").textValue(), payload.get("aud").textValue(), payload.get("resourceType").textValue(),
                payload.get("taskParametersSha256").textValue());
    }

    private Map<String, Object> row(ClaimedTaskView task) {
        return jdbc.queryForMap("SELECT * FROM task_execution_authorization WHERE execution_id = ?", task.executionId().toString());
    }

    private int executionCount() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM task_execution WHERE task_instance_id = ?", Integer.class, task.toString()));
    }

    private String taskStatus() {
        return jdbc.queryForObject("SELECT status FROM task_instance WHERE id = ?", String.class, task.toString());
    }

    private void readerIdentity() throws Exception {
        installIdentity("spiffe://fixture.test/reader", "reader-certificate", "reader-service");
    }

    private void executorIdentity() throws Exception {
        installIdentity("spiffe://fixture.test/executor", "executor-certificate", "task-executor-service");
    }

    private static void installIdentity(String uri, String encoded, String service) throws Exception {
        var certificate = mock(X509Certificate.class);
        when(certificate.getBasicConstraints()).thenReturn(-1);
        when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, uri)));
        when(certificate.getEncoded()).thenReturn(encoded.getBytes(StandardCharsets.US_ASCII));
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[]{certificate});
        request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, service);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SchedulerException.class, exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();

        /** 返回固定 UTC 时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        /** 测试统一使用 UTC。 */
        @Override public Clock withZone(ZoneId zone) { return this; }

        /** 返回测试推进后的时间。 */
        @Override public Instant instant() { return now; }
    }
}
