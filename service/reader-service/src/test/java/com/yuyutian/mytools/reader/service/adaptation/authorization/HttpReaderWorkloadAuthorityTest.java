package com.yuyutian.mytools.reader.service.adaptation.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.yuyutian.mytools.reader.config.ReaderWorkloadAuthorizationProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadIntrospection;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class HttpReaderWorkloadAuthorityTest {
    private static final String IDENTITY = "spiffe://fixture.test/reader";
    @TempDir static Path directory;
    private static String password;
    private static Path passwordFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger keyRequests = new AtomicInteger();
    private final AtomicInteger introspections = new AtomicInteger();
    private final AtomicReference<String> keyResponse = new AtomicReference<>();
    private final AtomicReference<String> activeResponse = new AtomicReference<>();
    private final AtomicReference<String> peerThumbprint = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> contentType = new AtomicReference<>("application/json");
    private final AtomicReference<String> encoding = new AtomicReference<>("identity");
    private final AtomicInteger delayMillis = new AtomicInteger();
    private HttpsServer server;
    private ExecutorService serverThreads;
    private KeyPair signing;
    private ReaderWorkloadTls tls;
    private HttpReaderWorkloadAuthority authority;

    @BeforeAll
    static void certificates() throws Exception {
        password = UUID.randomUUID().toString();
        passwordFile = directory.resolve("password");
        Files.writeString(passwordFile, password);
        ownerOnly(passwordFile);
        generate("server", "DNS:localhost", "serverAuth");
        generate("reader", "URI:" + IDENTITY, "clientAuth");
        generate("untrusted", "URI:" + IDENTITY, "clientAuth");
        trust("reader-trust", "server");
        trust("server-trust", "reader");
    }

    @BeforeEach
    void start() throws Exception {
        SSLContext context = context("server", "server-trust");
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            /** 强制由 TLS 握手验证 Reader 客户端证书。 */
            @Override public void configure(HttpsParameters parameters) {
                var ssl = context.getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverThreads);
        server.createContext("/api/internal/v1/task-execution-authorizations", exchange -> {
            try {
                byte[] certificate = ((HttpsExchange) exchange).getSSLSession().getPeerCertificates()[0].getEncoded();
                peerThumbprint.set(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate)));
                authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                boolean jwks = exchange.getRequestURI().getPath().endsWith("/jwks");
                (jwks ? keyRequests : introspections).incrementAndGet();
                if (delayMillis.get() > 0) Thread.sleep(delayMillis.get());
                byte[] body = (jwks ? keyResponse.get() : activeResponse.get()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType.get());
                exchange.getResponseHeaders().set("Content-Encoding", encoding.get());
                exchange.getResponseHeaders().set("Location", url() + "/api/internal/v1/task-execution-authorizations/jwks");
                exchange.sendResponseHeaders(responseStatus.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) {
                // 超时/限长夹具会主动断开连接；不得将测试请求内容写日志。
            } finally {
                exchange.close();
            }
        });
        server.start();
        signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keyResponse.set(jwks(Map.of("current", signing)));
        activeResponse.set(mapper.writeValueAsString(Map.of("active", true, "authorizedUntil", clock.now.plusSeconds(60).toString())));
        tls = new ReaderWorkloadTls(properties(url(), "reader"));
        authority = freshAuthority();
    }

    @AfterEach
    void stop() { server.stop(0); serverThreads.shutdownNow(); }

    @Test
    void shouldUseRealReaderTlsAndNeverCacheActiveResults() throws Exception {
        assertThat(authority.publicKey("current").getEncoded()).isEqualTo(signing.getPublic().getEncoded());
        assertThat(peerThumbprint.get()).isEqualTo(tls.thumbprint());
        assertThat(authority.publicKey("current")).isNotNull();
        assertThat(keyRequests.get()).isEqualTo(1);
        assertThat(authority.activeUntil(request())).isEqualTo(clock.now.plusSeconds(60));
        activeResponse.set("{\"active\":false,\"authorizedUntil\":null}");
        assertThat(authority.activeUntil(request())).isNull();
        assertThat(introspections.get()).isEqualTo(2);
        assertThat(mapper.readTree(requestBody.get()).size()).isEqualTo(9);
        assertThat(authorizationHeader.get()).isNull();
    }

    @Test
    void shouldRefreshUnknownKidAndBoundRotationRequests() throws Exception {
        authority.publicKey("current");
        var next = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keyResponse.set(jwks(Map.of("current", signing, "next", next)));
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> authority.publicKey("next"));
        assertThat(keyRequests.get()).isEqualTo(1);
        clock.now = clock.now.plusSeconds(6);
        assertThat(authority.publicKey("next").getEncoded()).isEqualTo(next.getPublic().getEncoded());
        assertThat(keyRequests.get()).isEqualTo(2);
        clock.now = clock.now.plusSeconds(301);
        responseStatus.set(503);
        expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> authority.publicKey("current"));
        expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> authority.publicKey("current"));
        assertThat(keyRequests.get()).isEqualTo(3);
    }

    @Test
    void shouldRejectMissingAndUntrustedClientCertificatesAndWrongHostname() throws Exception {
        var withoutCertificate = HttpClient.newBuilder().sslContext(context(null, "reader-trust")).build();
        var missing = new HttpReaderWorkloadAuthority(properties(url(), "reader"), withoutCertificate, mapper, clock);
        expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> missing.publicKey("current"));
        var unknown = new ReaderWorkloadTls(properties(url(), "untrusted"));
        expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE,
                () -> new HttpReaderWorkloadAuthority(properties(url(), "untrusted"), unknown.client(), mapper, clock).publicKey("current"));
        var wrongHostProperties = properties(url().replace("localhost", "127.0.0.1"), "reader");
        expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE,
                () -> new HttpReaderWorkloadAuthority(wrongHostProperties, tls.client(), mapper, clock).publicKey("current"));
        assertThat(keyRequests.get()).isZero();
    }

    @Test
    void shouldRejectUnsafeJwksBeforeReplacingAnyPublicKeys() throws Exception {
        var valid = mapper.readTree(jwks(Map.of("current", signing))).get("keys").get(0);
        var privateKey = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) privateKey).put("d", "not-allowed");
        var badAlgorithm = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) badAlgorithm).put("alg", "EdDSA");
        for (String invalid : List.of("{\"keys\":[]}", "{\"keys\":null}", "{\"keys\":[" + valid + "," + valid + "]}",
                "{\"keys\":[" + privateKey + "]}", "{\"keys\":[" + badAlgorithm + "]}",
                "{\"keys\":[],\"keys\":[" + valid + "]}", "{\"keys\":[" + valid + "]}{}", " ".repeat(8193))) {
            keyResponse.set(invalid);
            expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> freshAuthority().publicKey("current"));
        }
    }

    @Test
    void shouldRejectMalformedIntrospectionAndNeverFallBackOnHttpErrors() {
        for (String invalid : List.of("{\"active\":true}", "{\"active\":false,\"authorizedUntil\":\"2030-01-01T00:00:00Z\"}",
                "{\"active\":true,\"authorizedUntil\":null}", "{\"active\":false,\"authorizedUntil\":null,\"extra\":1}",
                "{\"active\":false,\"active\":true,\"authorizedUntil\":null}", " ".repeat(1025))) {
            activeResponse.set(invalid);
            expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> authority.activeUntil(request()));
        }
        for (int status : List.of(401, 403, 429, 503, 307)) {
            responseStatus.set(status);
            expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> authority.activeUntil(request()));
        }
    }

    @Test
    void shouldBoundTimeoutAndRejectCompressionMediaTypeAndTransactionCalls() {
        contentType.set("text/html");
        expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> authority.activeUntil(request()));
        contentType.set("application/json");
        encoding.set("gzip");
        expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> authority.activeUntil(request()));
        encoding.set("identity");
        delayMillis.set(1500);
        long started = System.nanoTime();
        expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> authority.activeUntil(request()));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1400);
        int sent = introspections.get();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            expect(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE, () -> authority.activeUntil(request()));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(introspections.get()).isEqualTo(sent);
    }

    private HttpReaderWorkloadAuthority freshAuthority() { return new HttpReaderWorkloadAuthority(properties(url(), "reader"), tls.client(), mapper, clock); }
    private String url() { return "https://localhost:" + server.getAddress().getPort(); }
    private static ReaderWorkloadAuthorizationProperties properties(String url, String key) {
        return new ReaderWorkloadAuthorizationProperties(true, url, "mytools-task-scheduler", 1, 300, path(key).toString(),
                passwordFile.toString(), path("reader-trust").toString(), passwordFile.toString(), IDENTITY, Set.of("spiffe://fixture.test/executor"));
    }
    private static WorkloadIntrospection request() {
        return new WorkloadIntrospection(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                "a".repeat(43), "reader-adaptation-internal", "CHAPTER_ADAPTATION", "b".repeat(64));
    }
    private String jwks(Map<String, KeyPair> keys) throws Exception {
        return mapper.writeValueAsString(Map.of("keys", keys.entrySet().stream().map(entry -> Map.of("kid", entry.getKey(),
                "kty", "OKP", "crv", "Ed25519", "alg", "Ed25519", "use", "sig", "x", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOfRange(entry.getValue().getPublic().getEncoded(), 12, 44)))).toList()));
    }
    private static void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ChapterAdaptationException.class,
                failure -> { assertThat(failure.errorCode()).isEqualTo(code); assertThat(failure.getCause()).isNull(); });
    }
    private static Path path(String name) { return directory.resolve(name + ".p12"); }
    private static void ownerOnly(Path path) throws Exception { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
    private static void generate(String name, String san, String eku) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "fixture",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=fixture", "-validity", "2", "-ext", "SAN=" + san, "-ext", "EKU=" + eku,
                "-ext", "KU=digitalSignature", "-keystore", path(name).toString(), "-storetype", "PKCS12", "-storepass:file", passwordFile.toString(), "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Fixture key generation timed out"); }
        assertThat(process.exitValue()).isZero();
        ownerOnly(path(name));
    }
    private static KeyStore load(String name) throws Exception {
        KeyStore result = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path(name))) { result.load(input, password.toCharArray()); }
        return result;
    }
    private static void trust(String name, String certificate) throws Exception {
        KeyStore result = KeyStore.getInstance("PKCS12");
        result.load(null, password.toCharArray());
        result.setCertificateEntry("trusted", load(certificate).getCertificate("fixture"));
        try (var output = Files.newOutputStream(path(name))) { result.store(output, password.toCharArray()); }
        ownerOnly(path(name));
    }
    private static SSLContext context(String key, String trust) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        if (key != null) keys.init(load(key), password.toCharArray());
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(load(trust));
        SSLContext result = SSLContext.getInstance("TLS");
        result.init(key == null ? null : keys.getKeyManagers(), trusts.getTrustManagers(), null);
        return result;
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T00:00:00Z");
        /** 返回 UTC 测试时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 维持固定 UTC 测试时区。 */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 返回可控当前时间。 */
        @Override public Instant instant() { return now; }
    }
}
