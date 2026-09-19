package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定本机调度夹具，验证请求最小化、完整响应期限和严格式解析；不连接真实 Provider。 */
class HttpAdaptationSchedulerGatewayTest {
    private static final String TOKEN = "fixture-reader-business-token";
    private final UUID adaptation = UUID.randomUUID();
    private final UUID task = UUID.randomUUID();
    private final List<Captured> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;
    private volatile String response;
    private volatile String contentType = "application/json";
    private volatile String encoding = "identity";
    private volatile int status = 200;
    private volatile boolean slowBody;

    @BeforeEach
    void start() throws IOException {
        response = valid();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldSendOnlyOpaqueIdAndBusinessIdentityWithNoBody() {
        var client = client(2, TOKEN);
        var accepted = client.submit(adaptation);
        assertThat(accepted.taskInstanceId()).isEqualTo(task);
        assertThat(client.find(adaptation)).isEqualTo(accepted);
        response = valid().replace("false", "true").replace("QUEUED", "CANCELLED");
        assertThat(client.cancel(adaptation).cancellationSettled()).isTrue();
        assertThat(requests).extracting(Captured::method).containsExactly("POST", "GET", "POST");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.body()).isEmpty();
            assertThat(request.service()).isEqualTo("reader-service");
            assertThat(request.token()).isEqualTo(TOKEN);
            assertThat(request.path()).startsWith("/api/v1/task-instances/reader-adaptations/" + adaptation);
        });
        assertThat(requests.getLast().path()).endsWith("/cancel");
    }

    @Test
    void shouldDistinguishMissingTaskFromConfirmedCancellation() {
        response = "{\"adaptationId\":\"" + adaptation + "\",\"taskInstanceId\":null,\"cancellationRecorded\":false,\"taskStatus\":null}";
        var client = client(2, TOKEN);
        assertThat(client.find(adaptation).cancellationSettled()).isFalse();
        expect(false, () -> client.submit(adaptation));
        response = response.replace("false", "true");
        assertThat(client.cancel(adaptation).cancellationSettled()).isTrue();
    }

    @Test
    void shouldRejectDuplicateTrailingUnknownAndMalformedFields() {
        var client = client(2, TOKEN);
        for (String invalid : List.of(valid() + " {}", valid().replace("{", "{\"taskStatus\":\"FAILED\","),
                valid().replace("}", ",\"text\":\"untrusted\"}"), valid().replace(adaptation.toString(), UUID.randomUUID().toString()),
                valid().replace(task.toString(), "1-1-1-1-1"), valid().replace("QUEUED", "UNKNOWN"), "", "null")) {
            response = invalid;
            expect(false, () -> client.find(adaptation));
        }
    }

    @Test
    void shouldRejectCompressedMislabelledAndOversizedResponses() {
        var client = client(2, TOKEN);
        encoding = "gzip";
        expect(false, () -> client.find(adaptation));
        encoding = "identity";
        contentType = "application/json-untrusted";
        expect(false, () -> client.find(adaptation));
        contentType = "application/json";
        response = " ".repeat(65537);
        expect(false, () -> client.find(adaptation));
    }

    @Test
    void shouldNotFollowRedirectAndShouldRetryOnlyTransientHttpStatus() {
        var client = client(2, TOKEN);
        status = 302;
        expect(false, () -> client.find(adaptation));
        assertThat(requests).hasSize(1);
        status = 401;
        expect(false, () -> client.find(adaptation));
        status = 429;
        expect(true, () -> client.find(adaptation));
        status = 503;
        expect(true, () -> client.find(adaptation));
    }

    @Test
    void shouldBoundWholeResponseAfterHeadersArrive() {
        slowBody = true;
        Instant start = Instant.now();
        expect(true, () -> client(1, TOKEN).submit(adaptation));
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void shouldRefuseMissingCredentialAndAmbientTransactionBeforeNetwork() {
        expect(false, () -> client(2, "").submit(adaptation));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            expect(false, () -> client(2, TOKEN).submit(adaptation));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(requests).isEmpty();
    }

    private HttpAdaptationSchedulerGateway client(int seconds, String token) {
        return new HttpAdaptationSchedulerGateway("http://127.0.0.1:" + server.getAddress().getPort(), seconds, new ObjectMapper(), token);
    }

    private String valid() {
        return "{\"adaptationId\":\"" + adaptation + "\",\"taskInstanceId\":\"" + task
                + "\",\"cancellationRecorded\":false,\"taskStatus\":\"QUEUED\"}";
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("X-Task-Service-Id"), exchange.getRequestHeaders().getFirst("X-Task-Business-Token")));
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Encoding", encoding);
        exchange.getResponseHeaders().set("Location", "/redirect-target");
        exchange.sendResponseHeaders(status, 0);
        try (var body = exchange.getResponseBody()) {
            if (slowBody) {
                body.write('{');
                body.flush();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            } else {
                body.write(response.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            // 预期超时或超限会主动关闭连接，夹具无需把关闭事件作为第二次失败。
        }
    }

    private static void expect(boolean retryable, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AdaptationSchedulerException.class, exception -> {
            assertThat(exception.retryable()).isEqualTo(retryable);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getMessage()).doesNotContain(TOKEN, "untrusted", "127.0.0.1");
        });
    }

    private record Captured(String method, String path, String body, String service, String token) {
        /** 测试失败时也不自动输出凭据和正文。 */
        @Override
        public String toString() {
            return "Captured[method=" + method + ", sensitive=redacted]";
        }
    }
}
