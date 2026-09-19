package com.yuyutian.mytools.task.executor.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.runtime.ScriptReleaseVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulerNodeClientTest {

    private HttpServer server;
    private final AtomicInteger heartbeatCount = new AtomicInteger();
    private UUID nodeId;
    private final AtomicReference<String> internalToken = new AtomicReference<>();
    private final AtomicReference<String> serviceId = new AtomicReference<>();
    private final List<String> claimRequestIds = new CopyOnWriteArrayList<>();
    private final List<Boolean> childTaskOnlyClaims = new CopyOnWriteArrayList<>();
    private final List<Boolean> rootTaskOnlyClaims = new CopyOnWriteArrayList<>();
    private final List<List<String>> parentTaskScopes = new CopyOnWriteArrayList<>();
    private final AtomicInteger claimCount = new AtomicInteger();
    private final AtomicBoolean forceClaimCapacityReservation = new AtomicBoolean();
    private final List<String> stepReportRequestIds = new CopyOnWriteArrayList<>();
    private final List<String> completionRequestIds = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> completionPayload = new AtomicReference<>();
    private final AtomicInteger stepReportCount = new AtomicInteger();
    private final AtomicInteger completionCount = new AtomicInteger();
    private final AtomicReference<String> nodeStatus = new AtomicReference<>();
    private final AtomicReference<String> nodeStatusReason = new AtomicReference<>();
    private final AtomicReference<String> nodeStatusPayload = new AtomicReference<>();
    private final AtomicInteger forcedReportStatus = new AtomicInteger();
    private final AtomicReference<String> registrationPayload = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        nodeId = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/execution-topology/nodes/register", this::handleRegister);
        server.createContext("/api/v1/execution-topology/nodes/", this::handleHeartbeat);
        server.createContext("/internal/v1/executions/claim", this::handleClaim);
        server.createContext("/internal/v1/executions/", this::handleExecutionReport);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldRegisterAndSendHeartbeat() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of("runtimes", "python3.12"), Map.of("gpu", false),
                java.util.Set.of("media"), false, Map.of()
        );
        SchedulerNodeClient client = new SchedulerNodeClient(properties, new ObjectMapper());
        UUID instanceId = UUID.randomUUID();

        ExecutorNodeRegistration registration = client.register(instanceId);
        client.heartbeat(registration.id(), instanceId, 2);

        assertEquals(nodeId, registration.id());
        assertEquals(1, heartbeatCount.get());
    }

    @Test
    void shouldReportNodeDrainingStatus() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        SchedulerNodeClient client = new SchedulerNodeClient(properties, new ObjectMapper());
        UUID instanceId = UUID.randomUUID();

        client.updateNodeStatus(nodeId, instanceId, "DRAINING", "EXECUTOR_DISK_PRESSURE");

        assertEquals("DRAINING", nodeStatus.get());
        assertEquals("EXECUTOR_DISK_PRESSURE", nodeStatusReason.get());
        assertEquals(new ObjectMapper().readTree("""
                {"status":"DRAINING","reason":"EXECUTOR_DISK_PRESSURE","expectedInstanceId":"%s"}
                """.formatted(instanceId)), new ObjectMapper().readTree(nodeStatusPayload.get()));
    }

    @Test
    void shouldFlattenNestedNodeLabelsForSchedulerMatching() {
        Map<String, Object> labels = SchedulerNodeClient.flattenedLabels(Map.of(
                "executor", Map.of("node", "executor-test"),
                "storage", Map.of("mount", Map.of("managed", "present"))));
        assertEquals("executor-test", labels.get("executor.node"));
        assertEquals("present", labels.get("storage.mount.managed"));
    }

    @Test
    void shouldSendInternalToken() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), "scheduler-secret",
                Path.of("runtime/tasks"), Path.of("scripts"), Path.of("sdk/python"),
                Path.of("/usr/bin/python3"), 10, 1, 60, 30, 4,
                Map.of(), Map.of(), java.util.Set.of(), false, Map.of());

        new SchedulerNodeClient(properties, new ObjectMapper()).register(UUID.randomUUID());

        assertEquals("scheduler-secret", internalToken.get());
        assertEquals("task-executor-service", serviceId.get());
    }

    @Test
    void shouldRegisterVerifiedScriptReleaseDigests() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of("runtimes", "python"), Map.of(), java.util.Set.of(), true, Map.of());
        ScriptReleaseVerifier verifier = mock(ScriptReleaseVerifier.class);
        when(verifier.releaseDigests()).thenReturn(Map.of("sample:1.0.0", "a".repeat(64)));

        new SchedulerNodeClient(properties, new ObjectMapper(), verifier).register(UUID.randomUUID());

        var payload = new ObjectMapper().readTree(registrationPayload.get());
        assertEquals("a".repeat(64),
                payload.path("capabilities").path("scriptReleases").path("sample:1.0.0").asText());
    }

    @Test
    void shouldReuseClaimRequestIdAfterResponseLoss() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        SchedulerNodeClient client = new SchedulerNodeClient(
                properties, new ObjectMapper().findAndRegisterModules());

        assertThrows(IOException.class, () -> client.claim(nodeId, UUID.randomUUID()));
        ClaimedTask claimed = client.claim(nodeId, UUID.randomUUID()).orElseThrow();

        assertEquals(2, claimRequestIds.size());
        assertEquals(claimRequestIds.get(0), claimRequestIds.get(1));
        assertEquals(List.of(false, false), childTaskOnlyClaims);
        assertTrue(claimed.mayCreateChildren());
    }

    @Test
    void shouldKeepFilteredClaimRequestIdSeparateFromGeneralClaims() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        SchedulerNodeClient client = new SchedulerNodeClient(
                properties, new ObjectMapper().findAndRegisterModules());
        UUID instanceId = UUID.randomUUID();

        assertThrows(IOException.class, () -> client.claim(nodeId, instanceId, true));
        assertTrue(client.claim(nodeId, instanceId, true).isPresent());
        assertTrue(client.claim(nodeId, instanceId).isPresent());
        assertTrue(client.claimRootTask(nodeId, instanceId).isPresent());
        UUID parentId = UUID.randomUUID();
        assertTrue(client.claimDirectChildTask(nodeId, instanceId, java.util.Set.of(parentId)).isPresent());

        assertEquals(List.of(true, true, false, false, true), childTaskOnlyClaims);
        assertEquals(List.of(false, false, false, true, false), rootTaskOnlyClaims);
        assertEquals(List.of(List.of(), List.of(), List.of(), List.of(), List.of(parentId.toString())),
                parentTaskScopes);
        assertEquals(claimRequestIds.get(0), claimRequestIds.get(1));
        assertTrue(!claimRequestIds.get(1).equals(claimRequestIds.get(2)));
    }

    @Test
    void shouldSurfaceCapacityReservationAndClearClaimRequestId() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        SchedulerNodeClient client = new SchedulerNodeClient(
                properties, new ObjectMapper().findAndRegisterModules());
        UUID instanceId = UUID.randomUUID();
        forceClaimCapacityReservation.set(true);

        assertThrows(ClaimCapacityReservedException.class,
                () -> client.claim(nodeId, instanceId, true));
        claimCount.set(1);
        assertTrue(client.claim(nodeId, instanceId, true).isPresent());

        assertEquals(2, claimRequestIds.size());
        assertTrue(!claimRequestIds.get(0).equals(claimRequestIds.get(1)));
    }

    @Test
    void shouldReuseStepAndCompletionRequestIdsAfterResponseLoss() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        SchedulerNodeClient client = new SchedulerNodeClient(
                properties, new ObjectMapper().findAndRegisterModules());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "main.py", List.of(), 30, "FAIL_TASK", 10, 1);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample",
                UUID.randomUUID(), 1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));

        assertThrows(IOException.class, () -> client.reportStep(
                task, step, 1, "SUCCEEDED", 0, Map.of("value", "ok"), null, null));
        client.reportStep(task, step, 1, "SUCCEEDED", 0, Map.of("value", "ok"), null, null);
        assertThrows(IOException.class, () -> client.complete(task, "SUCCEEDED"));
        client.complete(task, "SUCCEEDED");

        assertEquals(2, stepReportRequestIds.size());
        assertEquals(stepReportRequestIds.get(0), stepReportRequestIds.get(1));
        assertEquals(2, completionRequestIds.size());
        assertEquals(completionRequestIds.get(0), completionRequestIds.get(1));
    }

    @Test
    void shouldClassifyServerAndConflictReportErrors() {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        SchedulerNodeClient client = new SchedulerNodeClient(properties, new ObjectMapper());
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample",
                UUID.randomUUID(), 1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of());

        forcedReportStatus.set(503);
        SchedulerClientException unavailable = assertThrows(
                SchedulerClientException.class, () -> client.complete(task, "SUCCEEDED"));
        assertTrue(unavailable.retryable());
        assertEquals("SCHEDULER_UNAVAILABLE", unavailable.errorCode());

        forcedReportStatus.set(409);
        SchedulerClientException conflict = assertThrows(
                SchedulerClientException.class, () -> client.complete(task, "SUCCEEDED"));
        assertEquals(false, conflict.retryable());
        assertEquals("REPORT_CONFLICT", conflict.errorCode());
    }

    @Test
    void shouldSendCompensationSummaryWithStableCompletionRequestId() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), Path.of("/usr/bin/python3"), 10, 1, 60, 4,
                Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        SchedulerNodeClient client = new SchedulerNodeClient(properties, new ObjectMapper());
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample",
                UUID.randomUUID(), 1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of());
        ExecutionCompletion completion = new ExecutionCompletion(
                "FAILED", "FAILED", true, "REMOTE_ROLLBACK_FAILED");

        assertThrows(IOException.class, () -> client.complete(task, completion));
        client.complete(task, completion);

        assertEquals(completionRequestIds.get(0), completionRequestIds.get(1));
        var payload = new ObjectMapper().readTree(completionPayload.get());
        assertEquals("FAILED", payload.path("status").asText());
        assertEquals("FAILED", payload.path("compensationStatus").asText());
        assertTrue(payload.path("compensationRequired").asBoolean());
        assertEquals("REMOTE_ROLLBACK_FAILED", payload.path("compensationErrorCode").asText());
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        internalToken.set(exchange.getRequestHeaders().getFirst(SchedulerNodeClient.INTERNAL_TOKEN_HEADER));
        serviceId.set(exchange.getRequestHeaders().getFirst(SchedulerNodeClient.SERVICE_ID_HEADER));
        registrationPayload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String response = "{\"id\":\"" + nodeId + "\",\"name\":\"executor-test\",\"instanceId\":\"instance\"}";
        send(exchange, response);
    }

    private void handleHeartbeat(HttpExchange exchange) throws IOException {
        if ("PATCH".equals(exchange.getRequestMethod())) {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            nodeStatusPayload.set(requestBody);
            var request = new ObjectMapper().readTree(requestBody);
            nodeStatus.set(request.path("status").asText());
            nodeStatusReason.set(request.path("reason").asText());
            send(exchange, "{}");
            return;
        }
        if (exchange.getRequestHeaders().getFirst("X-Executor-Instance-Id") != null
                && "2".equals(exchange.getRequestHeaders().getFirst("X-Running-Tasks"))) {
            heartbeatCount.incrementAndGet();
        }
        send(exchange, "{}");
    }

    private void handleClaim(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var request = new ObjectMapper().readTree(requestBody);
        claimRequestIds.add(request.path("claimRequestId").asText());
        childTaskOnlyClaims.add(request.path("childTaskOnly").asBoolean());
        rootTaskOnlyClaims.add(request.path("rootTaskOnly").asBoolean());
        List<String> parentIds = new CopyOnWriteArrayList<>();
        request.path("parentTaskInstanceIds").forEach(value -> parentIds.add(value.asText()));
        parentTaskScopes.add(List.copyOf(parentIds));
        if (forceClaimCapacityReservation.compareAndSet(true, false)) {
            exchange.getResponseHeaders().add("X-MyTools-Claim-Blocked", "CAPACITY_RESERVED");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (claimCount.incrementAndGet() == 1) {
            exchange.close();
            return;
        }
        String parentTaskInstanceId = request.path("childTaskOnly").asBoolean()
                ? "\"" + (request.path("parentTaskInstanceIds").isEmpty()
                ? UUID.randomUUID() : UUID.fromString(request.path("parentTaskInstanceIds").get(0).asText())) + "\""
                : "null";
        String response = """
                {"executionId":"%s","taskInstanceId":"%s","parentTaskInstanceId":%s,
                 "taskName":"sample","leaseToken":"%s","fencingToken":1,
                 "leaseUntil":"%s","deadlineAt":"%s","mayCreateChildren":true,"parameters":{},"steps":[]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), parentTaskInstanceId, UUID.randomUUID(),
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
        send(exchange, response);
    }

    private void handleExecutionReport(HttpExchange exchange) throws IOException {
        int forcedStatus = forcedReportStatus.get();
        if (forcedStatus != 0) {
            String errorCode = forcedStatus >= 500 ? "SCHEDULER_UNAVAILABLE" : "REPORT_CONFLICT";
            send(exchange, "{\"code\":\"" + errorCode + "\"}", forcedStatus);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var request = new ObjectMapper().readTree(requestBody);
        if (path.endsWith("/steps/report")) {
            stepReportRequestIds.add(request.path("reportRequestId").asText());
            if (stepReportCount.incrementAndGet() == 1) {
                exchange.close();
                return;
            }
        } else if (path.endsWith("/complete")) {
            completionRequestIds.add(request.path("completionRequestId").asText());
            completionPayload.set(requestBody);
            if (completionCount.incrementAndGet() == 1) {
                exchange.close();
                return;
            }
        }
        send(exchange, "{\"outcome\":\"accepted\",\"replayed\":false}");
    }

    private void send(HttpExchange exchange, String response) throws IOException {
        send(exchange, response, 200);
    }

    private void send(HttpExchange exchange, String response, int statusCode) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
