package com.yuyutian.mytools.task.executor.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
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

import static org.junit.jupiter.api.Assertions.*;

class ExecutorWorkloadTlsTest {
    private static final String IDENTITY = "spiffe://fixture.test/executor";
    @TempDir static Path directory;
    private static Path passwordFile;
    private static String password;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicInteger responseCode = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> peerThumbprint = new AtomicReference<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private HttpsServer server;

    @BeforeAll
    static void createEphemeralCertificates() throws Exception {
        password = UUID.randomUUID().toString();
        passwordFile = directory.resolve("fixture-password");
        Files.writeString(passwordFile, password);
        ownerOnly(passwordFile);
        generate("server", "DNS:localhost", "serverAuth");
        generate("client", "URI:" + IDENTITY, "clientAuth");
        generate("untrusted", "URI:" + IDENTITY, "clientAuth");
        generate("wrong-eku", "URI:" + IDENTITY, "serverAuth");
        writeTrust("server-trust", "client");
        writeTrust("client-trust", "server");
        writeTrust("untrusted-trust", "untrusted");
    }

    @BeforeEach
    void startServer() throws Exception {
        SSLContext context = context("server", "server-trust");
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            /** 测试服务器强制真实 TLS 客户端证书验证。 */
            @Override public void configure(HttpsParameters parameters) {
                var ssl = context.getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/", exchange -> {
            try {
                byte[] certificate = ((HttpsExchange) exchange).getSSLSession().getPeerCertificates()[0].getEncoded();
                peerThumbprint.set(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate)));
                requests.incrementAndGet();
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "https://localhost:" + server.getAddress().getPort() + "/redirected");
                exchange.sendResponseHeaders(responseCode.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception exception) {
                throw new IOException("Fixture HTTPS handler failed");
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void shouldPresentRealClientCertificateAndVerifyServer() throws Exception {
        var tls = tls("client", "client-trust", IDENTITY);
        var response = send(tls.client(), url());
        assertEquals(200, response.statusCode());
        assertEquals(tls.thumbprint(), peerThumbprint.get());
        assertEquals(43, tls.thumbprint().length());
    }

    @Test
    void shouldRejectMissingClientCertificateUntrustedPeerAndWrongHostname() throws Exception {
        var noClient = HttpClient.newBuilder().sslContext(context(null, "client-trust")).build();
        assertThrows(IOException.class, () -> send(noClient, url()));
        assertThrows(IOException.class, () -> send(tls("untrusted", "client-trust", IDENTITY).client(), url()));
        assertThrows(IOException.class, () -> send(tls("client", "untrusted-trust", IDENTITY).client(), url()));
        assertThrows(IOException.class, () -> send(tls("client", "client-trust", IDENTITY).client(), url().replace("localhost", "127.0.0.1")));
        assertEquals(0, requests.get());
    }

    @Test
    void shouldNeverFollowRedirectsWithWorkloadIdentity() throws Exception {
        responseCode.set(307);
        assertEquals(307, send(tls("client", "client-trust", IDENTITY).client(), url()).statusCode());
        assertEquals(1, requests.get());
    }

    @Test
    void shouldFailStartupForUnsafeCredentialsOrWrongIdentity() throws Exception {
        assertThrows(IllegalStateException.class, () -> tls("client", "client-trust", "spiffe://fixture.test/other"));
        assertThrows(IllegalStateException.class, () -> tls("wrong-eku", "client-trust", IDENTITY));
        Path copy = directory.resolve("readable-client.p12");
        Files.copy(path("client"), copy);
        Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-r-----"));
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> tls("readable-client", "client-trust", IDENTITY));
        assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains(copy.toString()));
        Path link = directory.resolve("linked-client.p12");
        Files.createSymbolicLink(link, path("client"));
        assertThrows(IllegalStateException.class, () -> tls("linked-client", "client-trust", IDENTITY));
        assertThrows(IllegalStateException.class, () -> new ExecutorWorkloadTls(properties("http://localhost:1"),
                tlsProperties("client", "client-trust", IDENTITY)));
    }

    @Test
    void shouldLoadNoCredentialsWhenDisabled() {
        var disabled = new ExecutorWorkloadTls(properties("http://127.0.0.1:1"),
                new ExecutorWorkloadTlsProperties(false, "", "", "", "", ""));
        assertNull(disabled.thumbprint());
        assertEquals(HttpClient.Redirect.NEVER, disabled.client().followRedirects());
    }

    @Test
    void shouldReceiveAndRotateClaimsOverRealTlsWithoutLeakingIntoJson() throws Exception {
        var tls = tls("client", "client-trust", IDENTITY);
        var registry = new WorkloadAuthorizationRegistry(mapper, tls);
        var client = new SchedulerNodeClient(properties(url()), mapper, null, tls, registry);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var fixture = WorkloadFixtures.task(now, tls.thumbprint());
        responseBody.set(WorkloadFixtures.transport(fixture));
        var claimed = client.claim(UUID.randomUUID(), UUID.randomUUID()).orElseThrow();
        assertEquals(fixture.workloadAssertion(), registry.current(claimed.executionId()).token());
        assertFalse(mapper.writeValueAsString(claimed).contains("workloadAssertion"));
        String renewed = WorkloadFixtures.token(claimed, now, claimed.leaseUntil(), tls.thumbprint(), 2, Map.of());
        responseBody.set(mapper.writeValueAsString(Map.of("leaseUntil", claimed.leaseUntil().toString(),
                "cancelRequested", false, "leaseState", "ACTIVE", "workloadAssertion", renewed,
                "workloadAssertionExpiresAt", now.plusSeconds(60).toString())));
        assertEquals(renewed, client.heartbeatExecution(claimed).workloadAssertion());
        assertEquals(2, registry.current(claimed.executionId()).generation());
        client.releaseWorkloadAuthorization(claimed.executionId());
        assertThrows(IOException.class, () -> client.heartbeatExecution(claimed));
        assertThrows(IOException.class, () -> registry.current(claimed.executionId()));
        assertTrue(requestBodies.stream().noneMatch(body -> body.contains("workloadAssertion") || body.contains(renewed)));
    }

    @Test
    void shouldAbandonLostClaimKeyButRetainAmbiguousRequests() throws Exception {
        var tls = tls("client", "client-trust", IDENTITY);
        var client = new SchedulerNodeClient(properties(url()), mapper, null, tls, new WorkloadAuthorizationRegistry(mapper, tls));
        UUID node = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        responseCode.set(503);
        responseBody.set("{\"code\":\"WORKLOAD_AUTHORIZATION_UNAVAILABLE\"}");
        assertThrows(IOException.class, () -> client.claim(node, instance));
        assertThrows(IOException.class, () -> client.claim(node, instance));
        assertEquals(claimKey(0), claimKey(1));
        responseCode.set(409);
        responseBody.set("{\"code\":\"EXECUTION_LEASE_LOST\"}");
        assertThrows(IOException.class, () -> client.claim(node, instance));
        assertEquals(claimKey(1), claimKey(2));
        assertThrows(IOException.class, () -> client.claim(node, instance));
        assertNotEquals(claimKey(2), claimKey(3));
    }

    @Test
    void shouldCloseKnownRevocationAndSanitizeBadProtocolDiagnostics() throws Exception {
        var tls = tls("client", "client-trust", IDENTITY);
        var registry = new WorkloadAuthorizationRegistry(mapper, tls);
        var client = new SchedulerNodeClient(properties(url()), mapper, null, tls, registry);
        var fixture = WorkloadFixtures.task(Instant.now().truncatedTo(ChronoUnit.SECONDS), tls.thumbprint());
        responseBody.set(WorkloadFixtures.transport(fixture));
        var task = client.claim(UUID.randomUUID(), UUID.randomUUID()).orElseThrow();
        responseCode.set(409);
        responseBody.set("{\"code\":\"EXECUTION_LEASE_LOST\"}");
        assertThrows(SchedulerClientException.class, () -> client.heartbeatExecution(task));
        assertThrows(IOException.class, () -> registry.current(task.executionId()));
        responseBody.set("{\"code\":\"" + fixture.workloadAssertion() + "\"}");
        var failure = assertThrows(SchedulerClientException.class, () -> client.claim(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals("HTTP_409", failure.errorCode());
        assertFalse(failure.getMessage().contains(fixture.workloadAssertion()));
        responseCode.set(200);
        responseBody.set("{\"workloadAssertion\":\"" + fixture.workloadAssertion());
        IOException malformed = assertThrows(IOException.class, () -> client.claim(UUID.randomUUID(), UUID.randomUUID()));
        assertNull(malformed.getCause());
        assertFalse(malformed.getMessage().contains(fixture.workloadAssertion()));
    }

    private String claimKey(int index) throws Exception { return mapper.readTree(requestBodies.get(index)).path("claimRequestId").textValue(); }
    private String url() { return "https://localhost:" + server.getAddress().getPort(); }
    private ExecutorWorkloadTls tls(String key, String trust, String identity) {
        return new ExecutorWorkloadTls(properties(url()), tlsProperties(key, trust, identity));
    }
    private static ExecutorWorkloadTlsProperties tlsProperties(String key, String trust, String identity) {
        return new ExecutorWorkloadTlsProperties(true, path(key).toString(), passwordFile.toString(),
                path(trust).toString(), passwordFile.toString(), identity);
    }
    private static ExecutorProperties properties(String url) {
        return new ExecutorProperties("fixture", url, directory.resolve("tasks"), directory.resolve("scripts"),
                directory.resolve("sdk"), Path.of("/usr/bin/python3"), 10, 1, 60, 4, Map.of(), Map.of(), Set.of(), false, Map.of());
    }
    private static HttpResponse<String> send(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static Path path(String name) { return directory.resolve(name + ".p12"); }
    private static void ownerOnly(Path path) throws IOException { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
    private static void generate(String name, String san, String eku) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=fixture",
                "-validity", "2", "-ext", "SAN=" + san, "-ext", "EKU=" + eku, "-ext", "KU=digitalSignature",
                "-keystore", path(name).toString(), "-storetype", "PKCS12", "-storepass:file", passwordFile.toString(),
                "-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Fixture key generation timed out");
        }
        assertEquals(0, process.exitValue(), "Fixture key generation failed");
        ownerOnly(path(name));
    }
    private static KeyStore load(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path(name))) { store.load(input, password.toCharArray()); }
        return store;
    }
    private static void writeTrust(String name, String certificate) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, password.toCharArray());
        trust.setCertificateEntry("trusted", load(certificate).getCertificate("fixture"));
        try (var output = Files.newOutputStream(path(name))) { trust.store(output, password.toCharArray()); }
        ownerOnly(path(name));
    }
    private static SSLContext context(String key, String trust) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        if (key != null) keys.init(load(key), password.toCharArray());
        TrustManagerFactory trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusted.init(load(trust));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(key == null ? null : keys.getKeyManagers(), trusted.getTrustManagers(), null);
        return context;
    }
}
