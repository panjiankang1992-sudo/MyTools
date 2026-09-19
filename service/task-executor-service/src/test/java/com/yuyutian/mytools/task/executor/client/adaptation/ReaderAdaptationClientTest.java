package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionLease;
import com.yuyutian.mytools.task.executor.client.ExecutorWorkloadTls;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorWorkloadTlsProperties;
import com.yuyutian.mytools.task.executor.runtime.WorkloadAuthorizationRegistry;
import com.yuyutian.mytools.task.executor.runtime.WorkloadFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReaderAdaptationClientTest {
    private static final String IDENTITY = "spiffe://fixture.test/adaptation-executor";
    private static final NovelProviderModels.Deployment DEPLOYMENT = new NovelProviderModels.Deployment("fixture-deployment", "fixture-model", 1, 1048576, 1000, false);
    @TempDir static Path directory;
    private static String password;
    private static Path passwordFile;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> peer = new AtomicReference<>();
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> media = new AtomicReference<>("application/json");
    private HttpsServer server;
    private ExecutorWorkloadTls tls;
    private WorkloadAuthorizationRegistry registry;
    private ClaimedTask task;
    private ReaderAdaptationClient client;

    @BeforeAll
    static void certificates() throws Exception {
        password = UUID.randomUUID().toString(); passwordFile = directory.resolve("password");
        Files.writeString(passwordFile, password); ownerOnly(passwordFile);
        generate("server", "DNS:localhost", "serverAuth"); generate("client", "URI:" + IDENTITY, "clientAuth");
        trust("server-trust", "client"); trust("client-trust", "server");
    }

    @BeforeEach
    void initialize() throws Exception {
        SSLContext context = context();
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            /** 本机夹具必须实际收到受信客户端证书。 */
            @Override public void configure(HttpsParameters parameters) {
                var ssl = context.getDefaultSSLParameters(); ssl.setNeedClientAuth(true); parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/", exchange -> {
            try {
                byte[] certificate = ((HttpsExchange) exchange).getSSLSession().getPeerCertificates()[0].getEncoded();
                peer.set(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate)));
                Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Reader-Attempt-Settlement"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(request);
                String response = handler.get().respond(request);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", media.get());
                exchange.getResponseHeaders().set("Cache-Control", "no-store, private");
                exchange.getResponseHeaders().set("Location", "/untrusted-redirect");
                exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception exception) {
                throw new java.io.IOException("Reader TLS fixture failed");
            } finally { exchange.close(); }
        });
        server.start();
        var properties = new ExecutorProperties("fixture", url(), directory.resolve("tasks"), directory.resolve("scripts"),
                directory.resolve("sdk"), Path.of("/usr/bin/python3"), 10, 1, 60, 4, Map.of(), Map.of(), Set.of(), false, Map.of());
        tls = new ExecutorWorkloadTls(properties, new ExecutorWorkloadTlsProperties(true, path("client").toString(), passwordFile.toString(),
                path("client-trust").toString(), passwordFile.toString(), IDENTITY));
        registry = new WorkloadAuthorizationRegistry(mapper, tls);
        task = WorkloadFixtures.task(Instant.now().truncatedTo(ChronoUnit.SECONDS), tls.thumbprint()); registry.acceptClaim(task);
        client = new ReaderAdaptationClient(URI.create(url()), task, tls, registry);
    }

    @AfterEach
    void close() { server.stop(0); if (tls != null) tls.client().shutdownNow(); }

    @Test
    void sendsNativeMtlsAndUsesLatestHeartbeatForEachFixedScopeRead() throws Exception {
        handler.set(request -> {
            if (request.path().endsWith("/claim")) return json(Map.of("state", state(), "nextAction", "ANALYZE", "inputs", Map.of(), "context", Map.of()));
            if (request.path().endsWith("/workflow")) return workflow();
            return json(state());
        });
        assertThat(client.claim().body().path("nextAction").asText()).isEqualTo("ANALYZE");
        assertThat(peer.get()).isEqualTo(tls.thumbprint());
        String old = requests.getFirst().authorization();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String renewed = WorkloadFixtures.token(task, now, now.plusSeconds(90), tls.thumbprint(), 2, Map.of());
        registry.acceptHeartbeat(task, new ExecutionLease(now.plusSeconds(90), false, "ACTIVE", renewed, now.plusSeconds(60)));
        var workflow = client.workflow(); client.status();
        assertThat(workflow.toString()).isEqualTo("ReaderReply[REDACTED]");
        assertThat(requests.get(1).authorization()).isEqualTo("Bearer " + renewed).isNotEqualTo(old);
        for (Request request : requests) {
            assertThat(request.path()).startsWith(scope());
            assertThat(request.body()).isEmpty();
            assertThat(request.settlement()).isNull();
        }
        registry.close(task.executionId());
        assertThat(client.active()).isFalse();
        assertThatThrownBy(client::workflow).hasMessage("READER_044").hasNoCause();
        assertThat(requests).hasSize(3);
    }

    @Test
    void bindsReservationPermitAndImmutableSettlementEvenAfterAuthorizationCloses() throws Exception {
        UUID attempt = UUID.randomUUID();
        AtomicReference<String> providerId = new AtomicReference<>();
        byte[] nonce = new byte[32]; byte[] mac = new byte[32]; new java.security.SecureRandom().nextBytes(nonce); new java.security.SecureRandom().nextBytes(mac);
        String token = "settle-v1.fixture." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
        handler.set(request -> {
            if (request.path().endsWith("/send-started")) {
                Instant now = Instant.now();
                return json(Map.of("providerAttemptId", providerId.get(), "maySend", true, "transmission", 1,
                        "sendBy", now.plusSeconds(1), "callDeadlineAt", now.plusSeconds(60), "settlementExpiresAt", now.plusSeconds(180), "attemptSettlementToken", token));
            }
            var body = mapper.readTree(request.body());
            if (request.method().equals("PUT")) return json(Map.of("attemptId", attempt, "providerAttemptId", providerId.get(), "status", body.path("status").asText(),
                    "archivedOnly", request.authorization() == null, "terminalPayloadSha256", "c".repeat(64)));
            providerId.set(body.path("providerAttemptId").asText());
            return json(Map.of("attemptId", attempt, "providerAttemptId", providerId.get(), "attemptNo", 1, "callKind", "PLAN", "status", "REGISTERED",
                    "reservedMillis", 90000, "expiresAt", Instant.now().plusSeconds(30)));
        });
        try (var provider = provider()) {
            var ticket = ticket(provider);
            var reservation = client.reserve(ticket);
            assertThat(reservation.attemptId()).isEqualTo(attempt);
            assertThat(mapper.readTree(requests.getFirst().body()).path("requestSha256").asText()).isEqualTo(ticket.requestSha256());
            var capability = client.sendStarted(reservation);
            assertThat(capability.providerPermit().requestSha256()).isEqualTo(ticket.requestSha256());
            assertThat(capability.toString()).doesNotContain(token);
            registry.close(task.executionId());
            var terminal = new NovelStageOutput.Terminal("CALL_OUTCOME_UNKNOWN", null, null, null, null, null, null, 500, "READER_054", null);
            var settled = client.settle(capability, terminal);
            assertThat(settled.body().path("archivedOnly").booleanValue()).isTrue();
            assertThat(requests.getLast().authorization()).isNull();
            assertThat(requests.getLast().settlement()).isEqualTo(token);
            client.settle(capability, terminal);
            assertThat(requests.get(2).body()).isEqualTo(requests.get(3).body());
            assertThatThrownBy(() -> client.settle(capability, new NovelStageOutput.Terminal("FAILED", null, null, null, null, null, null, 403, "READER_055", null)))
                    .hasMessage("READER_036");
            assertThatThrownBy(() -> client.reserve(ticket)).hasMessage("READER_044");
            assertThat(requests).hasSize(4);
        }
    }

    @Test
    void fixedStoryCommandsCannotOverrideOutputOrFinalPass() throws Exception {
        UUID candidate = UUID.randomUUID(); UUID critic = UUID.randomUUID(); UUID validation = UUID.randomUUID();
        handler.set(request -> {
            if (request.path().endsWith("/attempts/recover")) return "{\"recovered\":1}";
            if (request.path().endsWith("/prepare-context") || request.path().endsWith("/constraints")) return "{}";
            if (request.path().endsWith("/validations")) return json(Map.of("candidateAttemptId", candidate, "criticAttemptId", critic, "progress", progress()));
            if (request.path().endsWith("/complete")) return json(Map.of("candidateAttemptId", candidate, "validationId", validation, "progress", progress()));
            return json(progress());
        });
        client.prepareContext(); client.seal(UUID.randomUUID()); client.progress(false); client.progress(true);
        client.validate(candidate, critic); client.complete(candidate, validation); client.fail(ErrorCode.PROTOCOL);
        assertThat(client.recover()).isEqualTo(1);
        assertThat(requests.get(2).body()).contains("GENERATING", "GENERATE", "CRITIC_PENDING");
        assertThat(requests.get(3).body()).contains("REPAIRING", "REPAIR", "CRITIC_PENDING");
        for (Request request : requests) assertThat(request.body()).doesNotContain("ownerId", "outputText", "PASS", "apiKey");
        assertThatThrownBy(() -> client.fail(ErrorCode.AUTHORITY_UNAVAILABLE)).hasMessage("READER_049");
    }

    @Test
    void rejectsMismatchedScopeAndUnsafeBaseBeforeExposingReaderResponse() {
        handler.set(request -> {
            ObjectNode state = mapper.valueToTree(state()); state.put("adaptationId", UUID.randomUUID().toString()); return json(state);
        });
        assertThatThrownBy(client::status).hasMessage("READER_049");
        for (String base : List.of("http://localhost", url() + "/arbitrary", url() + "?token=fixture", url() + "#fragment")) {
            assertThatThrownBy(() -> new ReaderAdaptationClient(URI.create(base), task, tls, registry)).hasMessage("READER_044");
        }
        assertThat(requests).hasSize(1);
    }

    @Test
    void dropsRemoteMessageAndDoesNotRetryOrFollowRedirect() {
        for (int code : List.of(503, 409, 302)) {
            status.set(code);
            handler.set(request -> "{\"code\":\"READER_059\",\"message\":\"fixture-secret-echo\"}");
            assertThatThrownBy(client::workflow).hasMessage("READER_059").hasNoCause();
        }
        assertThat(requests).hasSize(3);
        assertThat(requests).allMatch(request -> request.path().endsWith("/workflow"));
    }

    @Test
    void refusesMalformedDuplicateOversizedAndWrongMediaReaderResponses() {
        for (String response : List.of("{} {}", "{\"state\":{},\"state\":{}}", "{\"data\":\"" + "x".repeat(4194304) + "\"}")) {
            handler.set(request -> response);
            assertThatThrownBy(client::workflow).isInstanceOf(ReaderAdaptationException.class).hasNoCause();
        }
        media.set("text/html"); handler.set(request -> "<html>fixture-secret</html>");
        assertThatThrownBy(client::workflow).hasMessage("READER_059").hasNoCause();
    }

    @Test
    void revocationDuringReaderResponsePreventsContentFromLeavingClient() {
        handler.set(request -> { registry.close(task.executionId()); return workflow(); });
        assertThatThrownBy(client::workflow).hasMessage("READER_044").hasNoCause();
        assertThat(requests).hasSize(1);
    }

    @Test
    void actualCapabilitySurvivesEncryptedRestartAndUsesOnlyNarrowMtlsPut() throws Exception {
        UUID attempt = UUID.randomUUID(); AtomicReference<String> providerId = new AtomicReference<>();
        String token = "settle-v1.fixture." + randomDigest() + "." + randomDigest();
        handler.set(request -> {
            if (request.path().endsWith("/send-started")) {
                Instant now = Instant.now();
                return json(Map.of("providerAttemptId", providerId.get(), "maySend", true, "transmission", 1,
                        "sendBy", now.plusSeconds(1), "callDeadlineAt", now.plusSeconds(60), "settlementExpiresAt", now.plusSeconds(180), "attemptSettlementToken", token));
            }
            var body = mapper.readTree(request.body());
            if (request.method().equals("PUT")) return json(Map.of("attemptId", attempt, "providerAttemptId", providerId.get(), "status", body.path("status").asText(),
                    "archivedOnly", request.authorization() == null, "terminalPayloadSha256", "c".repeat(64)));
            providerId.set(body.path("providerAttemptId").asText());
            return json(Map.of("attemptId", attempt, "providerAttemptId", providerId.get(), "attemptNo", 1, "callKind", "PLAN", "status", "REGISTERED",
                    "reservedMillis", 90000, "expiresAt", Instant.now().plusSeconds(30)));
        });
        Path root = Files.createDirectory(directory.toRealPath().resolve("relay-" + UUID.randomUUID()),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path key = directory.toRealPath().resolve("relay-key-" + UUID.randomUUID()); byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
        Files.write(key, bytes); ownerOnly(key);
        try (var provider = provider(); var relay = new ReaderSettlementRelay(root, key)) {
            var capability = client.sendStarted(client.reserve(ticket(provider)));
            var exported = client.relay(capability);
            assertThat(exported.token).isEqualTo(token); assertThat(exported.certificate).isEqualTo(tls.thumbprint());
            var another = new ReaderAdaptationClient(URI.create(url()), task, tls, registry);
            assertThatThrownBy(() -> another.relay(capability)).hasMessage(ErrorCode.FENCED.code());
            relay.arm(client, capability).record(ReaderRelayEnvelope.unknown());
        }
        registry.close(task.executionId());
        try (var restarted = new ReaderSettlementRelay(root, key)) {
            assertThat(restarted.recoverOne(new ReaderSettlementClient(URI.create(url()), tls))).isEqualTo(ReaderSettlementRelay.Recovery.REPLAYED);
            assertThat(restarted.pendingCount()).isZero();
        }
        assertThat(requests).hasSize(3); assertThat(peer.get()).isEqualTo(tls.thumbprint());
        Request restored = requests.getLast();
        assertThat(restored.method()).isEqualTo("PUT"); assertThat(restored.path()).isEqualTo(scope() + "attempts/" + providerId.get());
        assertThat(restored.authorization()).isNull(); assertThat(restored.settlement()).isEqualTo(token);
        assertThat(mapper.readTree(restored.body()).path("status").asText()).isEqualTo("CALL_OUTCOME_UNKNOWN");
    }

    @Test
    void settlementTransportRejectsForeignOriginCertificateAndExpiryBeforeHttp() {
        ReaderRelayEnvelope entry = envelope(URI.create(url()), tls.thumbprint(), Instant.now().plusSeconds(120));
        var consumer = new ReaderSettlementClient(URI.create(url()), tls);
        assertThatThrownBy(() -> consumer.settle(envelope(URI.create("https://foreign.fixture.test/"), entry.certificate, entry.expiresAt))).hasMessage(ErrorCode.FENCED.code());
        assertThatThrownBy(() -> consumer.settle(envelope(entry.origin, randomDigest(), entry.expiresAt))).hasMessage(ErrorCode.FENCED.code());
        assertThatThrownBy(() -> consumer.settle(envelope(entry.origin, entry.certificate, Instant.now().minusSeconds(1)))).hasMessage(ErrorCode.FENCED.code());
        assertThat(requests).isEmpty();
    }

    @Test
    void settlementTransportBoundsResponseAndNeverRetriesRedirectsOrEchoesErrors() {
        ReaderRelayEnvelope entry = envelope(URI.create(url()), tls.thumbprint(), Instant.now().plusSeconds(120));
        var consumer = new ReaderSettlementClient(URI.create(url()), tls);
        for (int code : List.of(503, 409, 302)) {
            status.set(code); handler.set(request -> "{\"code\":\"READER_059\",\"message\":\"fixture-secret-echo\"}");
            assertThatThrownBy(() -> consumer.settle(entry)).hasMessage(ErrorCode.AUTHORITY_UNAVAILABLE.code()).hasNoCause();
        }
        status.set(200);
        for (String response : List.of("{} {}", "{\"status\":\"SUCCEEDED\",\"status\":\"FAILED\"}", "{\"data\":\"" + "x".repeat(8192) + "\"}")) {
            handler.set(request -> response);
            assertThatThrownBy(() -> consumer.settle(entry)).hasMessage(ErrorCode.AUTHORITY_UNAVAILABLE.code()).hasNoCause();
        }
        assertThat(requests).hasSize(6);
        assertThat(requests).allSatisfy(request -> { assertThat(request.method()).isEqualTo("PUT"); assertThat(request.authorization()).isNull(); });
    }

    private ReaderRelayEnvelope envelope(URI origin, String certificate, Instant expiry) {
        return new ReaderRelayEnvelope(origin, certificate, UUID.fromString(task.parameters().get("adaptationId").toString()), task.executionId(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), expiry, "settle-v1.fixture." + randomDigest() + "." + randomDigest(), ReaderRelayEnvelope.unknown());
    }
    private static String randomDigest() { byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    private Map<String, Object> state() {
        return Map.ofEntries(Map.entry("adaptationId", task.parameters().get("adaptationId")), Map.entry("executionId", task.executionId()),
                Map.entry("fencingToken", task.fencingToken()), Map.entry("status", "ANALYZING"), Map.entry("currentStage", "PLAN_PENDING"),
                Map.entry("deadlineAt", task.deadlineAt().minusSeconds(60)), Map.entry("stopRequested", false), Map.entry("callsRemaining", 5),
                Map.entry("retriesRemaining", 2), Map.entry("providerMillisRemaining", 660000), Map.entry("pendingAttempts", List.of()));
    }
    private Map<String, Object> progress() { return Map.of("adaptationId", task.parameters().get("adaptationId"), "status", "VALIDATING", "currentStage", "CRITIC_PENDING", "version", 4); }
    private String workflow() {
        ObjectNode result = mapper.createObjectNode(); result.set("state", mapper.valueToTree(state())); result.putNull("errorCode");
        result.putArray("attempts"); result.putNull("constraints"); result.putNull("review"); return json(result);
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("Fixture encoding failed"); } }
    private String url() { return "https://localhost:" + server.getAddress().getPort(); }
    private String scope() { return "/api/internal/v1/chapter-adaptations/" + task.parameters().get("adaptationId") + "/executions/" + task.executionId() + "/"; }
    private NovelProviderClient provider() throws Exception {
        Path key = directory.toRealPath().resolve("provider-" + UUID.randomUUID()); Files.writeString(key, "fixture-" + UUID.randomUUID()); ownerOnly(key);
        // 该客户端仅构造请求与摘要，本测试不调用 Provider 网络。
        return new NovelProviderClient(DEPLOYMENT, key, URI.create("http://127.0.0.1:1/chat/completions"), Clock.systemUTC(), Duration.ofSeconds(1));
    }
    private static NovelProviderClient.Ticket ticket(NovelProviderClient provider) {
        return provider.prepare(UUID.randomUUID(), DEPLOYMENT.scope(), new NovelAdaptationPrompts().plan(NovelAdaptationPromptsTest.context("INITIAL", "Ari closed the door.", null)));
    }
    private record Request(String method, String path, String authorization, String settlement, String body) {
        /** 测试失败诊断也不回显正文或令牌。 */
        @Override public String toString() { return "FixtureRequest[REDACTED]"; }
    }
    @FunctionalInterface private interface Handler { String respond(Request request) throws Exception; }
    private static Path path(String name) { return directory.resolve(name + ".p12"); }
    private static void ownerOnly(Path file) throws Exception { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
    private static void generate(String name, String san, String eku) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=fixture", "-validity", "2", "-ext", "SAN=" + san, "-ext", "EKU=" + eku, "-ext", "KU=digitalSignature",
                "-keystore", path(name).toString(), "-storetype", "PKCS12", "-storepass:file", passwordFile.toString(), "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Fixture certificate generation timed out"); }
        assertThat(process.exitValue()).isZero(); ownerOnly(path(name));
    }
    private static KeyStore load(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12"); try (var input = Files.newInputStream(path(name))) { store.load(input, password.toCharArray()); } return store;
    }
    private static void trust(String name, String cert) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, password.toCharArray()); store.setCertificateEntry("trusted", load(cert).getCertificate("fixture"));
        try (var output = Files.newOutputStream(path(name))) { store.store(output, password.toCharArray()); } ownerOnly(path(name));
    }
    private static SSLContext context() throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(load("server"), password.toCharArray());
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trusts.init(load("server-trust"));
        SSLContext context = SSLContext.getInstance("TLS"); context.init(keys.getKeyManagers(), trusts.getTrustManagers(), null); return context;
    }
}
