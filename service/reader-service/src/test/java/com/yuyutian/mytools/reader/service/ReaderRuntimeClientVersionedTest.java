package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.reader.config.ReaderChapterContentProperties;
import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.ReaderRuntimeInvocation;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository.SourceExecutionSnapshot;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅与本机固定夹具通信，验证版本化运行时的完整 HTTP 边界。 */
class ReaderRuntimeClientVersionedTest {

    private static final String SOURCE_URL = "https://source.example.invalid";
    private static final String BOOK_URL = "https://book.example.invalid/book";
    private static final String CHAPTER_URL = "https://book.example.invalid/chapter/1";
    private static final String CONTENT_PATH = "/reader3/getBookContent";
    private static final String CATALOG_PATH = "/reader3/getChapterList";
    private final UUID sourceId = UUID.randomUUID();
    private final List<Captured> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean slowBody;
    private volatile boolean compressed;
    private volatile boolean redirect;

    @BeforeEach
    void startFixture() throws IOException {
        responses.put("/reader3/getBookInfo", "{\"isSuccess\":true,\"data\":{\"name\":\"Book\"}}");
        responses.put(CATALOG_PATH, "{\"isSuccess\":true,\"data\":[{\"title\":\"First\",\"url\":\""
                + CHAPTER_URL + "\",\"index\":19}]}");
        responses.put(CONTENT_PATH, "{\"isSuccess\":true,\"data\":\"First paragraph.<br>Second paragraph.\"}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopFixture() {
        releaseBody.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldInstallExactSnapshotIntoDifferentNamespacesAndIgnoreReportedIndex() {
        var first = invocation(1);
        var second = invocation(2);
        var client = client(120000, 2097152, 30);
        var catalog = client.catalog(first, snapshot(1), BOOK_URL);
        var content = client.content(second, snapshot(2), CHAPTER_URL);
        assertThat(catalog.chapters()).hasSize(1);
        assertThat(catalog.chapters().getFirst().index()).isZero();
        assertThat(content.text()).isEqualTo("First paragraph.\nSecond paragraph.");
        assertThat(requests).hasSize(7);
        assertThat(requests.subList(0, 4)).extracting(Captured::namespace).containsOnly(first.namespace());
        assertThat(requests.subList(4, 7)).extracting(Captured::namespace).containsOnly(second.namespace());
        assertThat(requests.get(1).body()).contains("rule-v1").doesNotContain("rule-v2");
        assertThat(requests.get(5).body()).contains("rule-v2").doesNotContain("rule-v1");
    }

    @Test
    void shouldRejectWrongVersionOrUncontrolledLocatorBeforeInstallingAnyRule() {
        var client = client(120000, 2097152, 30);
        assertThatThrownBy(() -> client.catalog(invocation(1), snapshot(2), BOOK_URL))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> client.content(invocation(1), snapshot(1), "file:///private/book.txt"))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> client.content(invocation(1), snapshot(1), "https://user:password@example.invalid/chapter"))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void shouldRejectDuplicateLocatorOrMissingChapterRatherThanDroppingItems() {
        String item = "{\"title\":\"Chapter\",\"url\":\"" + CHAPTER_URL + "\"}";
        responses.put(CATALOG_PATH, "{\"isSuccess\":true,\"data\":[" + item + "," + item + "]}");
        var client = client(120000, 2097152, 30);
        assertThatThrownBy(() -> client.catalog(invocation(1), snapshot(1), BOOK_URL))
                .isInstanceOf(ChapterAdaptationException.class);
        responses.put(CATALOG_PATH, "{\"isSuccess\":true,\"data\":[" + item + ",{}]}");
        assertThatThrownBy(() -> client.catalog(invocation(1), snapshot(1), BOOK_URL))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldRejectDecodedContentAboveCodepointBudgetWithoutTruncation() {
        assertThatThrownBy(() -> client(8, 2097152, 30).content(invocation(1), snapshot(1), CHAPTER_URL))
                .isInstanceOfSatisfying(ChapterAdaptationException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.ADAPTATION_CONTENT_TOO_LARGE));
    }

    @Test
    void shouldBoundBytesDuringChunkedResponseAndDiscardCandidate() {
        responses.put(CONTENT_PATH, "{\"isSuccess\":true,\"data\":\"" + "a".repeat(4096) + "\"}");
        assertThatThrownBy(() -> client(120000, 1024, 30).content(invocation(1), snapshot(1), CHAPTER_URL))
                .isInstanceOfSatisfying(ChapterAdaptationException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.ADAPTATION_CONTENT_TOO_LARGE));
    }

    @Test
    void shouldRejectDuplicateJsonKeysCompressionAndRedirects() {
        var client = client(120000, 2097152, 30);
        responses.put(CONTENT_PATH, "{\"isSuccess\":false,\"isSuccess\":true,\"data\":\"Text\"}");
        assertThatThrownBy(() -> client.content(invocation(1), snapshot(1), CHAPTER_URL))
                .isInstanceOf(ReaderRuntimeUnavailableException.class);
        responses.put(CONTENT_PATH, "{\"isSuccess\":true,\"data\":\"Text\"}");
        compressed = true;
        assertThatThrownBy(() -> client.content(invocation(1), snapshot(1), CHAPTER_URL))
                .isInstanceOf(ReaderRuntimeUnavailableException.class);
        compressed = false;
        redirect = true;
        assertThatThrownBy(() -> client.content(invocation(1), snapshot(1), CHAPTER_URL))
                .isInstanceOf(ReaderRuntimeUnavailableException.class);
    }

    @Test
    void shouldTimeOutSlowBodyEvenAfterSuccessfulResponseHeaders() {
        slowBody = true;
        Instant started = Instant.now();
        assertThatThrownBy(() -> client(120000, 2097152, 1).content(invocation(1), snapshot(1), CHAPTER_URL))
                .isInstanceOf(ReaderRuntimeUnavailableException.class);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    private ReaderRuntimeClient client(int codepoints, int bytes, int timeout) {
        var properties = new ReaderProperties("http://127.0.0.1", "", "reader",
                "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-runtime-key", "http://127.0.0.1", "",
                1000, 1000, false, Set.of());
        return new ReaderRuntimeClient(new ObjectMapper(), properties,
                new ReaderChapterContentProperties(50000, 33554432, bytes, codepoints, timeout));
    }

    private ReaderRuntimeInvocation invocation(int version) {
        return new ReaderRuntimeInvocation(41L, sourceId, version, UUID.randomUUID());
    }

    private SourceExecutionSnapshot snapshot(int version) {
        return new SourceExecutionSnapshot(sourceId, SOURCE_URL, version,
                Map.of("bookSourceUrl", SOURCE_URL, "ruleContent", Map.of("content", "rule-v" + version)));
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(new Captured(exchange.getRequestHeaders().getFirst("X-User-NS"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        boolean content = CONTENT_PATH.equals(path);
        if (content && compressed) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        if (content && redirect) {
            exchange.getResponseHeaders().set("Location", "https://redirect.example.invalid");
        }
        byte[] body = responses.getOrDefault(path, "{\"isSuccess\":true}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(content && redirect ? 302 : 200, 0);
        try (var output = exchange.getResponseBody()) {
            if (content && slowBody) {
                output.write(' ');
                output.flush();
                try {
                    releaseBody.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            output.write(body);
        } catch (IOException exception) {
            // 客户端容量拒绝或超时后主动关闭连接属于夹具的预期路径。
        }
    }

    private record Captured(String namespace, String body) {
    }
}
