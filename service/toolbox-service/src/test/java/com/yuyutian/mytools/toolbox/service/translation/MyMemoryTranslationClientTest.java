package com.yuyutian.mytools.toolbox.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 通过内存传输替身验证请求与响应边界，不访问任何网络。 */
class MyMemoryTranslationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StubHttpClient http;
    private MyMemoryTranslationClient client;

    @BeforeEach
    void setUp() {
        http = new StubHttpClient();
        client = new MyMemoryTranslationClient(objectMapper, http);
    }

    @Test
    void sendsEncodedChineseUsingOnlyFixedGetQuery() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        reply(200, "application/json; charset=UTF-8", document("ice cream"), sent, null);
        String source = "\u51b0\u6dc7\u6dcb & + ?/#";

        assertThat(client.translate(source, true)).isEqualTo("ice cream");

        HttpRequest request = sent.get();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().getScheme()).isEqualTo("https");
        assertThat(request.uri().getHost()).isEqualTo("api.mymemory.translated.net");
        assertThat(request.uri().getPath()).isEqualTo("/get");
        assertThat(request.uri().getFragment()).isNull();
        assertThat(request.uri().getRawQuery().split("&")).hasSize(2);
        String[] fields = request.uri().getRawQuery().split("&");
        assertThat(URLDecoder.decode(fields[0], StandardCharsets.UTF_8)).isEqualTo("q=" + source);
        assertThat(URLDecoder.decode(fields[1], StandardCharsets.UTF_8)).isEqualTo("langpair=zh-CN|en");
        assertThat(request.bodyPublisher()).isEmpty();
        assertThat(request.timeout()).contains(Duration.ofSeconds(4));
        assertThat(request.headers().firstValue("Accept")).contains("application/json");
        assertThat(request.headers().firstValue("Accept-Encoding")).contains("identity");
        assertThat(request.headers().map()).hasSize(2);
    }

    @Test
    void translatesEnglishInOppositeDirection() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        reply(200, "application/json", document("\u82f9\u679c"), sent, null);

        assertThat(client.translate("apple", false)).isEqualTo("\u82f9\u679c");
        assertThat(URLDecoder.decode(sent.get().uri().getRawQuery(), StandardCharsets.UTF_8))
                .isEqualTo("q=apple&langpair=en|zh-CN");
    }

    @Test
    void keepsValidPrimaryWithoutReplacingItWithMemoryMatches() throws Exception {
        reply(documentWithMatches("\u8d70", List.of(memoryMatch("walk", "\u884c\u8d70", "en-US", "zh-CN", 1))));
        assertThat(client.translate("walk", false)).isEqualTo("\u8d70");
    }

    @Test
    void fallsBackFromPinyinExplanationToExactSourceAndLanguageMatch() throws Exception {
        reply(documentWithMatches("\u8d70 (z\u01d2u)", List.of(
                memoryMatch("walk", "\u8d70 (z\u01d2u)", "en-GB", "zh-CN", 1),
                memoryMatch("walk", "\u8d70", "en-US", "zh-CN", 0.99),
                memoryMatch("Walk", "\u884c\u8d70", "en-GB", "zh-CN", 0.98))));
        assertThat(client.translate("walk", false)).isEqualTo("\u8d70");
    }

    @Test
    void fallbackAlsoRespectsChineseToEnglishDirectionAndDecodesEntities() throws Exception {
        reply(documentWithMatches("\u8d70 walk", List.of(
                memoryMatch("\u8d70", "walk", "zh-CN", "en-GB", 0.99))));
        assertThat(client.translate("\u8d70", true)).isEqualTo("walk");
        reply(documentWithMatches("\u513f\u7ae5\u73a9\u5177", List.of(
                memoryMatch("\u513f\u7ae5\u73a9\u5177", "children&#39;s toys", "zh-Hans", "en", 1))));
        assertThat(client.translate("\u513f\u7ae5\u73a9\u5177", true)).isEqualTo("children's toys");
    }

    @Test
    void selectsHighestValidMatchThenQualityWithoutRelaxingEligibility() throws Exception {
        var lowQuality = new java.util.HashMap<>(memoryMatch("walk", "\u884c\u8d70", "en-GB", "zh-CN", 1));
        lowQuality.put("quality", "20");
        var highQuality = new java.util.HashMap<>(memoryMatch("walk", "\u8d70", "en-US", "zh-CN", 1));
        highQuality.put("quality", "74");
        reply(documentWithMatches("\u8d70 (z\u01d2u)", List.of(
                memoryMatch("walk", "\u6563\u6b65", "en-GB", "zh-CN", 0.99), lowQuality, highQuality)));
        assertThat(client.translate("walk", false)).isEqualTo("\u8d70");
    }

    @ParameterizedTest
    @MethodSource("invalidMemoryMatches")
    void rejectsUnsafeOrUnrelatedMemoryMatches(Map<String, Object> match) throws Exception {
        reply(documentWithMatches("\u8d70 (z\u01d2u)", List.of(match)));
        assertThatThrownBy(() -> client.translate("walk", false))
                .isInstanceOf(IOException.class).hasMessageStartingWith("Invalid translation").hasNoCause();
    }

    private static Stream<Map<String, Object>> invalidMemoryMatches() {
        return Stream.of(
                memoryMatch("Walk", "\u8d70", "en-GB", "zh-CN", 1),
                memoryMatch("walking", "\u8d70", "en-GB", "zh-CN", 1),
                memoryMatch("wa lk", "\u8d70", "en-GB", "zh-CN", 1),
                memoryMatch("walk", "\u8d70", "fr-FR", "zh-CN", 1),
                memoryMatch("walk", "\u8d70", "en-GB-untrusted", "zh-CN", 1),
                memoryMatch("walk", "\u8d70", "en-GB", "zh-TW", 1),
                memoryMatch("walk", "\u8d70", "en-GB", "zh", 1),
                memoryMatch("walk", "\u8d70", "zh-CN", "en-US", 1),
                memoryMatch("walk", "\u8d70", "en-GB", "zh-CN", 0.98),
                memoryMatch("walk", "\u8d70", "en-GB", "zh-CN", 1.01),
                memoryMatch("walk", "\u8d70", "en-GB", "zh-CN", "1"),
                memoryMatch("walk", "\u8d70 (walk)", "en-GB", "zh-CN", 1),
                memoryMatch("walk", "\u8d70 (z\u01d2u)", "en-GB", "zh-CN", 1),
                memoryMatch("walk", "walk", "en-GB", "zh-CN", 1),
                memoryMatch("walk", "\u8d70\u200b", "en-GB", "zh-CN", 1),
                memoryMatch("walk", "&lt;b&gt;\u8d70&lt;/b&gt;", "en-GB", "zh-CN", 1),
                memoryMatch("walk", "\u8d70".repeat(501), "en-GB", "zh-CN", 1),
                memoryMatch("walk", " ", "en-GB", "zh-CN", 1),
                Map.<String, Object>of("segment", "walk", "translation", "\u8d70", "source", "en-GB", "target", "zh-CN"));
    }

    @Test
    void doesNotUseMatchesWhenResponseIsUnavailableOrQuotaIsExhausted() throws Exception {
        byte[] valid = documentWithMatches("\u8d70 (z\u01d2u)", List.of(memoryMatch("walk", "\u8d70", "en-US", "zh-CN", 1)));
        var unavailable = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(valid);
        unavailable.put("responseStatus", 429);
        reply(objectMapper.writeValueAsBytes(unavailable));
        assertThatThrownBy(() -> client.translate("walk", false)).isInstanceOf(IOException.class)
                .hasMessage("Translation service unavailable");
        unavailable.put("responseStatus", 200).put("quotaFinished", true);
        reply(objectMapper.writeValueAsBytes(unavailable));
        assertThatThrownBy(() -> client.translate("walk", false)).isInstanceOf(IOException.class)
                .hasMessage("Translation service unavailable");
        reply(429, "application/json", valid, null, null);
        assertThatThrownBy(() -> client.translate("walk", false)).isInstanceOf(IOException.class)
                .hasMessage("Translation service rejected request");
    }

    @Test
    void missingOrNonTextualMemoryFieldsCannotBypassValidation() throws Exception {
        for (String field : List.of("segment", "translation", "source", "target")) {
            var invalid = new java.util.HashMap<>(memoryMatch("walk", "\u8d70", "en-US", "zh-CN", 1));
            invalid.put(field, 42);
            reply(documentWithMatches("\u8d70 (z\u01d2u)", List.of(invalid)));
            assertThatThrownBy(() -> client.translate("walk", false)).isInstanceOf(IOException.class);
            invalid.remove(field);
            reply(documentWithMatches("\u8d70 (z\u01d2u)", List.of(invalid)));
            assertThatThrownBy(() -> client.translate("walk", false)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void decodesEntitiesAtMostTwiceAndTrimsResult() throws Exception {
        reply(document("  children&amp;#39;s &amp;amp; toys  "));
        assertThat(client.translate("\u513f\u7ae5\u73a9\u5177", true)).isEqualTo("children's & toys");

        reply(document("children&amp;amp;#39;s"));
        assertThat(client.translate("\u513f\u7ae5\u7684", true)).isEqualTo("children&#39;s");
    }

    @Test
    void countsSupplementaryCharactersAsSingleCodePoints() throws Exception {
        String translation = "\uD842\uDFB7".repeat(500);
        reply(document(translation));
        assertThat(client.translate("a name", false)).isEqualTo(translation);
    }

    @ParameterizedTest
    @MethodSource("invalidTranslations")
    void rejectsInvalidTranslatedContent(String translated, String source, boolean chineseSource) throws Exception {
        reply(document(translated));
        assertThatThrownBy(() -> client.translate(source, chineseSource))
                .isInstanceOf(IOException.class).hasMessageStartingWith("Invalid translation").hasNoCause();
    }

    private static Stream<Arguments> invalidTranslations() {
        return Stream.of(
                Arguments.of("  ", "apple", false),
                Arguments.of("a".repeat(501), "\u82f9\u679c", true),
                Arguments.of("\uD842\uDFB7".repeat(501), "a name", false),
                Arguments.of("1234", "\u82f9\u679c", true),
                Arguments.of("\u82f9\u679c apple", "\u82f9\u679c", true),
                Arguments.of("fruit", "apple", false),
                Arguments.of("<b>apple</b>", "\u82f9\u679c", true),
                Arguments.of("&amp;lt;b&amp;gt;apple&amp;lt;/b&amp;gt;", "\u82f9\u679c", true),
                Arguments.of("apple\nfruit", "\u82f9\u679c", true),
                Arguments.of("apple\u0000fruit", "\u82f9\u679c", true),
                Arguments.of("apple\u200b", "\u82f9\u679c", true),
                Arguments.of("apple\ud800", "\u82f9\u679c", true),
                Arguments.of(" a P p L e ", "Apple", true),
                Arguments.of("\u82f9\u00a0\u679c", "\u82f9\u679c", false));
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void rejectsUnsuccessfulOrMalformedJson(String body) throws Exception {
        reply(body.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> client.translate("apple", false))
                .isInstanceOf(IOException.class).hasNoCause();
    }

    private static Stream<String> invalidDocuments() {
        return Stream.of(
                "not json",
                "null",
                "[]",
                "{\"responseStatus\":429,\"responseData\":{\"translatedText\":\"fruit\"}}",
                "{\"responseStatus\":200,\"quotaFinished\":true,\"responseData\":{\"translatedText\":\"fruit\"}}",
                "{\"responseStatus\":200,\"responseData\":{\"translatedText\":42}}",
                "{\"responseStatus\":200}",
                "{\"responseStatus\":200,\"responseData\":{\"translatedText\":\"fruit\"}} {}");
    }

    @Test
    void rejectsHttpErrorsWithoutIncludingProviderBody() throws Exception {
        reply(429, "application/json", "private upstream diagnostic".getBytes(StandardCharsets.UTF_8), null, null);
        assertThatThrownBy(() -> client.translate("apple", false))
                .isInstanceOf(IOException.class).hasMessage("Translation service rejected request").hasNoCause();
    }

    @Test
    void rejectsNonJsonContentType() throws Exception {
        reply(200, "text/html", document("\u82f9\u679c"), null, null);
        assertThatThrownBy(() -> client.translate("apple", false))
                .isInstanceOf(IOException.class).hasMessage("Invalid translation response type");
    }

    @Test
    void rejectsEmptyAndOversizedCompleteBodies() throws Exception {
        reply(new byte[0]);
        assertThatThrownBy(() -> client.translate("apple", false))
                .isInstanceOf(IOException.class).hasMessage("Invalid translation response size");
        reply(new byte[65_537]);
        assertThatThrownBy(() -> client.translate("apple", false))
                .isInstanceOf(IOException.class).hasMessage("Translation request failed");
    }

    @Test
    void stopsStreamingImmediatelyWhenCumulativeBytesExceedLimit() throws Exception {
        AtomicReference<HttpResponse.BodyHandler<?>> captured = new AtomicReference<>();
        reply(200, "application/json", document("\u82f9\u679c"), null, captured);
        client.translate("apple", false);
        HttpResponse.BodySubscriber<?> subscriber = captured.get().apply(null);
        StubSubscription subscription = new StubSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[32_768]), ByteBuffer.wrap(new byte[32_768])));
        assertThat(subscriber.getBody().toCompletableFuture()).isNotDone();

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));

        assertThat(subscription.cancelled).isTrue();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(IOException.class);
    }

    @Test
    void allowsExactlyTheStreamingLimitOnlyAfterComplete() throws Exception {
        AtomicReference<HttpResponse.BodyHandler<?>> captured = new AtomicReference<>();
        reply(200, "application/json", document("\u82f9\u679c"), null, captured);
        client.translate("apple", false);
        HttpResponse.BodySubscriber<?> subscriber = captured.get().apply(null);
        subscriber.onSubscribe(new StubSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[65_536])));
        assertThat(subscriber.getBody().toCompletableFuture()).isNotDone();
        subscriber.onComplete();
        assertThat((byte[]) subscriber.getBody().toCompletableFuture().join()).hasSize(65_536);
    }

    @Test
    void boundsFullResponseWaitAndCancelsTimedOutRequest() {
        http.abortWithInterruption = false;

        assertThatThrownBy(() -> client.translate("apple", false))
                .isInstanceOf(IOException.class).hasMessage("Translation request timed out").hasNoCause();
        assertThat(http.pending.isCancelled()).isTrue();
    }

    @Test
    void propagatesInterruptionAndCancelsPendingRequest() {
        http.abortWithInterruption = true;

        assertThatThrownBy(() -> client.translate("apple", false)).isInstanceOf(InterruptedException.class);
        assertThat(http.pending.isCancelled()).isTrue();
    }

    @Test
    void sanitizesUpstreamTimeoutAndTransportErrors() {
        http.failure = new HttpTimeoutException("private timeout context");
        assertThatThrownBy(() -> client.translate("apple", false))
                .isInstanceOf(IOException.class).hasMessage("Translation request timed out").hasNoCause();

        http.failure = new IOException("private transport context");
        assertThatThrownBy(() -> client.translate("apple", false))
                .isInstanceOf(IOException.class).hasMessage("Translation request failed").hasNoCause();
    }

    @Test
    void validatesUtf8InputLimitBeforeSending() {
        for (String value : new String[]{null, "", "  ", "a".repeat(501), "\u82f9".repeat(167)}) {
            assertThatThrownBy(() -> client.translate(value, false))
                    .isInstanceOf(IOException.class).hasMessage("Invalid translation input");
        }
        assertThat(http.calls).isZero();
    }

    private byte[] document(String translation) throws IOException {
        var root = objectMapper.createObjectNode();
        root.put("responseStatus", 200).put("quotaFinished", false);
        root.putObject("responseData").put("translatedText", translation);
        return objectMapper.writeValueAsBytes(root);
    }

    private byte[] documentWithMatches(String translation, List<Map<String, Object>> matches) throws IOException {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(document(translation));
        root.set("matches", objectMapper.valueToTree(matches));
        return objectMapper.writeValueAsBytes(root);
    }

    private static Map<String, Object> memoryMatch(String segment, String translation, String source, String target, Object match) {
        return Map.of("segment", segment, "translation", translation, "source", source, "target", target,
                "match", match, "quality", "74");
    }

    private void reply(byte[] body) {
        reply(200, "application/json", body, null, null);
    }

    private void reply(int status, String contentType, byte[] body, AtomicReference<HttpRequest> sent,
                       AtomicReference<HttpResponse.BodyHandler<?>> handler) {
        http.status = status;
        http.headers = HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (name, value) -> true);
        http.bytes = body;
        http.sent = sent;
        http.capturedHandler = handler;
    }

    /** 最小内存响应，只提供客户端会读取的状态、响应头和字节。 */
    private record StubResponse<T>(int statusCode, HttpHeaders headers, T body) implements HttpResponse<T> {
        /** 此替身不关联真实请求。 */
        @Override
        public HttpRequest request() { return null; }

        /** 不模拟重定向历史。 */
        @Override
        public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }

        /** 不创建 TLS 会话。 */
        @Override
        public Optional<SSLSession> sslSession() { return Optional.empty(); }

        /** 返回测试占位地址，不会访问网络。 */
        @Override
        public URI uri() { return URI.create("https://example.invalid/"); }

        /** 返回固定协议版本。 */
        @Override
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /** 立即模拟完整响应等待失败，避免测试真实等待四秒。 */
    private static final class AbortedFuture<T> extends CompletableFuture<HttpResponse<T>> {
        private final boolean interrupted;

        private AbortedFuture(boolean interrupted) {
            this.interrupted = interrupted;
        }

        /** 校验等待上限，并模拟取消或超时分支。 */
        @Override
        public HttpResponse<T> get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            assertThat(unit.toMillis(timeout)).isEqualTo(4_000);
            if (interrupted) {
                throw new InterruptedException("Test interruption");
            }
            throw new TimeoutException("Test timeout");
        }
    }

    /** 记录背压与取消，不连接外部响应流。 */
    private static final class StubSubscription implements Flow.Subscription {
        private boolean cancelled;

        /** 测试数据由用例主动提供。 */
        @Override
        public void request(long count) { }

        /** 记录上游取消。 */
        @Override
        public void cancel() { cancelled = true; }
    }

    /** 使用真实响应订阅器处理内存数据，避免依赖 JVM 动态附加代理。 */
    private static final class StubHttpClient extends HttpClient {
        private int calls;
        private int status;
        private HttpHeaders headers;
        private byte[] bytes;
        private AtomicReference<HttpRequest> sent;
        private AtomicReference<HttpResponse.BodyHandler<?>> capturedHandler;
        private Boolean abortWithInterruption;
        private IOException failure;
        private CompletableFuture<?> pending;

        /** 将测试响应交给客户端提供的真实订阅器。 */
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            calls++;
            if (sent != null) {
                sent.set(request);
            }
            if (capturedHandler != null) {
                capturedHandler.set(handler);
            }
            if (abortWithInterruption != null) {
                AbortedFuture<T> aborted = new AbortedFuture<>(abortWithInterruption);
                pending = aborted;
                return aborted;
            }
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            HttpResponse.BodySubscriber<T> subscriber = handler.apply(null);
            subscriber.onSubscribe(new StubSubscription());
            subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
            subscriber.onComplete();
            return subscriber.getBody().toCompletableFuture()
                    .thenApply(body -> new StubResponse<>(status, headers, body));
        }

        /** 当前客户端不使用服务端推送。 */
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                               HttpResponse.PushPromiseHandler<T> pushHandler) {
            return sendAsync(request, handler);
        }

        /** 禁止测试悄悄切换到未实现的同步传输。 */
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("Synchronous transport is not used");
        }

        /** 不提供测试 cookie。 */
        @Override
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }

        /** 返回固定连接等待上限。 */
        @Override
        public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(2)); }

        /** 不允许重定向。 */
        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }

        /** 不配置代理。 */
        @Override
        public Optional<ProxySelector> proxy() { return Optional.empty(); }

        /** 测试不建立 TLS 连接。 */
        @Override
        public SSLContext sslContext() { return null; }

        /** 返回空 TLS 参数。 */
        @Override
        public SSLParameters sslParameters() { return new SSLParameters(); }

        /** 不提供任何认证配置。 */
        @Override
        public Optional<Authenticator> authenticator() { return Optional.empty(); }

        /** 返回固定协议版本。 */
        @Override
        public Version version() { return Version.HTTP_1_1; }

        /** 不创建异步工作线程。 */
        @Override
        public Optional<Executor> executor() { return Optional.empty(); }
    }
}
