package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Deployment;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Permit;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Phase;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Result;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Scope;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** 专属宿主使用的有界单次客户端；无自动重试，也不接受任务提供的端点或认证配置。 */
public final class NovelProviderClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NovelProviderClient.class);
    private static final URI ENDPOINT = URI.create("https://api.sillytraven.dev/api/ai/v1/chat/completions");
    private final Deployment deployment;
    private final NovelProviderCredential credential;
    private final HttpClient client;
    private final URI endpoint;
    private final Clock clock;
    private final Duration firstByteTimeout;
    private final boolean contentCompatibility;
    private final boolean quoteProtocol;
    private final boolean insertionProtocol;
    private final Semaphore slots = new Semaphore(2);
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 仅加载已登记的精确模型和宿主凭据，构造过程不访问外部 Provider。 */
    public NovelProviderClient(Deployment deployment, Path credentialFile, String header, String prefix) {
        this(deployment, credentialFile, header, prefix, false);
    }

    /** 显式启用已验收的 Provider 方言；阶段总时限和一次发送约束保持不变。 */
    public NovelProviderClient(Deployment deployment, Path credentialFile, String header, String prefix, boolean contentCompatibility) {
        this(deployment, credentialFile, header, prefix, contentCompatibility, false);
    }

    /** 显式选择引文协议；旧输出契约仍可独立使用，不隐式升级运行中的调用。 */
    public NovelProviderClient(Deployment deployment, Path credentialFile, String header, String prefix,
                               boolean contentCompatibility, boolean quoteProtocol) {
        this(deployment, credentialFile, header, prefix, contentCompatibility, quoteProtocol, false);
    }

    /** 显式启用有界增补及固定检查表，旧冻结调用仍由原有协议处理。 */
    public NovelProviderClient(Deployment deployment, Path credentialFile, String header, String prefix,
                               boolean contentCompatibility, boolean quoteProtocol, boolean insertionProtocol) {
        this(deployment, new NovelProviderCredential(credentialFile, header, prefix), ENDPOINT,
                Clock.systemUTC(), Duration.ofSeconds(contentCompatibility ? 90 : 60), contentCompatibility, quoteProtocol, insertionProtocol);
    }

    NovelProviderClient(Deployment deployment, Path credentialFile, URI fixtureEndpoint, Clock clock,
                        Duration firstByteTimeout) {
        this(deployment, new NovelProviderCredential(credentialFile, "X-Auth-Fixture", ""),
                fixture(fixtureEndpoint), clock, firstByteTimeout, false, false);
    }

    NovelProviderClient(Deployment deployment, Path credentialFile, URI fixtureEndpoint, Clock clock,
                        Duration firstByteTimeout, boolean contentCompatibility) {
        this(deployment, new NovelProviderCredential(credentialFile, "X-Auth-Fixture", ""),
                fixture(fixtureEndpoint), clock, firstByteTimeout, contentCompatibility, false);
    }

    NovelProviderClient(Deployment deployment, Path credentialFile, URI fixtureEndpoint, Clock clock,
                        Duration firstByteTimeout, boolean contentCompatibility, boolean quoteProtocol) {
        this(deployment, new NovelProviderCredential(credentialFile, "X-Auth-Fixture", ""),
                fixture(fixtureEndpoint), clock, firstByteTimeout, contentCompatibility, quoteProtocol);
    }

    private NovelProviderClient(Deployment deployment, NovelProviderCredential credential, URI endpoint,
                                Clock clock, Duration firstByteTimeout, boolean contentCompatibility, boolean quoteProtocol) {
        this(deployment, credential, endpoint, clock, firstByteTimeout, contentCompatibility, quoteProtocol, false);
    }

    NovelProviderClient(Deployment deployment, Path credentialFile, URI fixtureEndpoint, Clock clock,
                        Duration firstByteTimeout, boolean contentCompatibility, boolean quoteProtocol, boolean insertionProtocol) {
        this(deployment, new NovelProviderCredential(credentialFile, "X-Auth-Fixture", ""), fixture(fixtureEndpoint), clock,
                firstByteTimeout, contentCompatibility, quoteProtocol, insertionProtocol);
    }

    private NovelProviderClient(Deployment deployment, NovelProviderCredential credential, URI endpoint,
                                Clock clock, Duration firstByteTimeout, boolean contentCompatibility, boolean quoteProtocol, boolean insertionProtocol) {
        if (quoteProtocol && !contentCompatibility) throw new NovelProviderException(ErrorCode.CONTEXT);
        if (insertionProtocol && !quoteProtocol) throw new NovelProviderException(ErrorCode.CONTEXT);
        this.deployment = deployment;
        this.credential = credential;
        this.endpoint = endpoint;
        this.clock = clock;
        this.firstByteTimeout = firstByteTimeout;
        this.contentCompatibility = contentCompatibility;
        this.quoteProtocol = quoteProtocol;
        this.insertionProtocol = insertionProtocol;
        SSLParameters tls = new SSLParameters();
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).sslParameters(tls)
                .proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    /** 在 Reader 预留之前构造固定请求和摘要，不把正文、密钥或原始 JSON 暴露给任务参数。 */
    public Ticket prepare(UUID providerAttemptId, Scope readerDeployment, NovelAdaptationPrompts.Prepared prompt) {
        if (closed.get() || providerAttemptId == null || prompt == null || !deployment.scope().equals(readerDeployment)) {
            throw new NovelProviderException(ErrorCode.CONTEXT);
        }
        boolean rewrite = prompt.fullRewrite();
        if (rewrite) prompt = prompt.rewriteProtocol();
        else {
            if (quoteProtocol) prompt = prompt.quoteProtocol();
            if (insertionProtocol) prompt = prompt.insertionProtocol();
        }
        Phase phase = prompt.phase();
        String systemPrompt = prompt.system();
        String userContext = prompt.user();
        NovelProviderJson.text(systemPrompt, 16000);
        NovelProviderJson.text(userContext, 400000);
        ObjectNode request = NovelProviderJson.MAPPER.createObjectNode();
        request.put("model", deployment.model()).put("stream", false).put("temperature", phase.temperature())
                .put("max_tokens", deployment.maximumOutputTokens()).put("n", 1);
        boolean structured = phase == Phase.PLAN || phase == Phase.CRITIC || (!rewrite && insertionProtocol);
        if (contentCompatibility && structured) request.putObject("response_format").put("type", "json_object");
        var messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userContext);
        byte[] bytes = NovelProviderJson.encode(request).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > deployment.maximumRequestBytes()) throw new NovelProviderException(ErrorCode.TOO_LARGE);
        String evidence = (rewrite || contentCompatibility) && structured
                ? NovelProviderJson.parse(userContext).path(phase == Phase.PLAN ? "original" : "candidate").textValue() : null;
        String constraintSha = (rewrite || quoteProtocol) && phase == Phase.CRITIC
                ? NovelProviderJson.parse(userContext).path("constraintSha256").textValue() : null;
        return new Ticket(this, providerAttemptId, phase, bytes, evidence, constraintSha, prompt.insertionScope(), rewrite);
    }

    /** 对外只返回已通过对应阶段字段白名单的 Reader 结算载荷。 */
    public NovelStageOutput.Terminal execute(Ticket ticket, Permit permit, BooleanSupplier stillAuthorized) {
        Result result = send(ticket, permit, stillAuthorized);
        if (ticket.rewrite) result = ticket.phase == Phase.PLAN || ticket.phase == Phase.CRITIC
                ? NovelProviderContentCodec.decodeBounded(ticket.phase, result, ticket.evidence, ticket.constraintSha, null)
                : NovelProviderContentCodec.decode(ticket.phase, result, null);
        else if (insertionProtocol) result = NovelProviderContentCodec.decodeBounded(ticket.phase, result,
                ticket.evidence, ticket.constraintSha, ticket.insertionScope);
        else if (quoteProtocol) result = ticket.phase == Phase.CRITIC
                ? NovelProviderContentCodec.decodeReferences(result, ticket.evidence, ticket.constraintSha)
                : NovelProviderContentCodec.decodeQuotes(ticket.phase, result, ticket.evidence, ticket.constraintSha);
        else if (contentCompatibility) result = NovelProviderContentCodec.decode(ticket.phase, result, ticket.evidence);
        return new NovelStageOutput().normalize(ticket.phase, result);
    }

    /** 只消费一次当前 Reader 许可；取消或超时后不重发，已发送结果交回独立结算链。 */
    Result send(Ticket ticket, Permit permit, BooleanSupplier stillAuthorized) {
        Instant now = clock.instant();
        if (closed.get() || ticket == null || ticket.owner != this || permit == null || stillAuthorized == null
                || !ticket.id.equals(permit.providerAttemptId()) || !ticket.sha.equals(permit.requestSha256())
                || !permit.maySend() || permit.sendBy() == null || !now.isBefore(permit.sendBy())
                || permit.sendBy().isAfter(now.plusSeconds(2)) || permit.callDeadlineAt() == null
                || !now.isBefore(permit.callDeadlineAt()) || permit.callDeadlineAt().isAfter(now.plusSeconds(ticket.phase.seconds()))
                || !authorized(stillAuthorized) || !ticket.consumed.compareAndSet(false, true)) {
            throw new NovelProviderException(ErrorCode.FENCED);
        }
        if (!slots.tryAcquire()) return NovelProviderResponseParser.failure(ErrorCode.UNAVAILABLE, null, null);
        CompletableFuture<HttpResponse<byte[]>> future = null;
        AtomicInteger status = new AtomicInteger();
        AtomicBoolean receivedByte = new AtomicBoolean();
        long start = System.nanoTime();
        long remainingNanos = Duration.between(now, permit.callDeadlineAt()).toNanos();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remainingNanos))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", deployment.acceptSse() ? "application/json, text/event-stream" : "application/json")
                    .header("Accept-Encoding", "identity").POST(new OncePublisher(ticket.bytes));
            credential.apply(request);
            // 在真正进入 HTTP 栈前再次检查短发送窗口和授权，不能因构建请求耗时而延迟发送。
            if (!clock.instant().isBefore(permit.sendBy()) || !authorized(stillAuthorized)) {
                return NovelProviderResponseParser.failure(ErrorCode.FENCED, null, null);
            }
            future = client.sendAsync(request.build(), info -> {
                status.set(info.statusCode());
                return new LimitedBody(info.headers(), receivedByte);
            });
            while (true) {
                long elapsed = System.nanoTime() - start;
                if (closed.get() || !authorized(stillAuthorized) || elapsed >= remainingNanos
                        || !clock.instant().isBefore(permit.callDeadlineAt())
                        || !receivedByte.get() && elapsed >= firstByteTimeout.toNanos()) {
                    future.cancel(true);
                    return NovelProviderResponseParser.failure(ErrorCode.UNKNOWN, optionalStatus(status), null);
                }
                try {
                    HttpResponse<byte[]> response = future.get(Math.max(1, Math.min(100,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos - elapsed))), TimeUnit.MILLISECONDS);
                    String type = response.statusCode() >= 200 && response.statusCode() < 300 ? media(response.headers()) : null;
                    Result result = new NovelProviderResponseParser().parse(response.statusCode(), type,
                            response.body(), deployment.model(), deployment.acceptSse());
                    // 防止 Provider 将认证值回显为看似合法的成功正文或请求身份。
                    if (credential.reflectedBy(result.content()) || credential.reflectedBy(result.providerRequestId())) {
                        return NovelProviderResponseParser.failure(ErrorCode.PROTOCOL, response.statusCode(), result.diagnosticSha256());
                    }
                    return result;
                } catch (TimeoutException exception) {
                    // 短等待用于响应取消及首字节期限，不构成 Provider 重试。
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return NovelProviderResponseParser.failure(ErrorCode.UNKNOWN, optionalStatus(status), null);
        } catch (Exception exception) {
            // 仅记录固定分类与本地调用身份；禁止记录异常对象、消息、外部地址或请求正文。
            log.warn("Novel provider transport failed: attemptId={}, phase={}, failure={}",
                    ticket.id, ticket.phase, transportFailure(exception));
            // 完整状态行可用于分类明确拒绝；成功响应的协议故障与没有响应的模糊网络故障分开。
            if (status.get() >= 300) return NovelProviderResponseParser.httpFailure(status.get(), null);
            boolean protocol = causeIsProtocol(exception);
            return NovelProviderResponseParser.failure(protocol ? ErrorCode.PROTOCOL : ErrorCode.UNKNOWN,
                    optionalStatus(status), null);
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            slots.release();
        }
    }

    /** 关闭连接和可擦除凭据；不再接受新的发送票据。 */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) { client.shutdownNow(); credential.close(); }
    }

    /** 宿主内存中的一次性发送票据，只向账本暴露摘要和调用身份。 */
    public static final class Ticket {
        private final NovelProviderClient owner;
        private final UUID id;
        private final Phase phase;
        private final byte[] bytes;
        private final String sha;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final String evidence;
        private final String constraintSha;
        private final NovelInsertionProtocol.Scope insertionScope;
        private final boolean rewrite;
        private Ticket(NovelProviderClient owner, UUID id, Phase phase, byte[] bytes, String evidence, String constraintSha, NovelInsertionProtocol.Scope insertionScope, boolean rewrite) {
            this.owner = owner; this.id = id; this.phase = phase; this.bytes = bytes; this.evidence = evidence;
            this.constraintSha = constraintSha;
            this.insertionScope = insertionScope;
            this.rewrite = rewrite;
            sha = NovelProviderJson.sha(bytes);
        }
        /** 返回发送字节的 SHA-256，认证头不参与正文摘要。 */
        public String requestSha256() { return sha; }
        /** 返回本次 Reader 预留身份。 */
        public UUID providerAttemptId() { return id; }
        /** 返回请求构建时已固定的阶段，不由调用预留命令重新声明。 */
        public Phase phase() { return phase; }
        /** 不允许序列化正文或凭据对象。 */
        @JsonIgnore @Override public String toString() { return "NovelProviderTicket[REDACTED]"; }
    }

    /** 阻止 JDK 在同一请求上重新订阅并重新发送章节字节。 */
    static final class OncePublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final AtomicBoolean used = new AtomicBoolean();
        OncePublisher(byte[] bytes) { delegate = HttpRequest.BodyPublishers.ofByteArray(bytes); }
        /** 返回精确长度。 */
        @Override public long contentLength() { return delegate.contentLength(); }
        /** 只允许第一次订阅；重试订阅不接触原正文。 */
        @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            if (used.compareAndSet(false, true)) { delegate.subscribe(subscriber); return; }
            subscriber.onSubscribe(new Flow.Subscription() {
                /** 拒绝重发时不再拉取任何字节。 */
                @Override public void request(long amount) { }
                /** 此订阅不持有正文资源。 */
                @Override public void cancel() { }
            });
            subscriber.onError(new NovelProviderException(ErrorCode.UNKNOWN));
        }
    }

    /** 在复制字节前检查长度，超限立即取消订阅，不进行解压或无界聚合。 */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final AtomicBoolean received;
        private Flow.Subscription subscription;
        LimitedBody(HttpHeaders headers, AtomicBoolean received) {
            this.received = received;
            try {
                int headerSize = 0;
                for (var entry : headers.map().entrySet()) {
                    headerSize += entry.getKey().length();
                    for (String value : entry.getValue()) headerSize += value.length();
                }
                if (headerSize > 8192 || headers.map().size() > 64) throw protocol();
                var lengths = headers.allValues("Content-Length");
                if (lengths.size() > 1 || !lengths.isEmpty() && (!lengths.getFirst().matches("[0-9]{1,9}")
                        || Long.parseLong(lengths.getFirst()) > NovelProviderResponseParser.MAXIMUM_BYTES)) throw protocol();
                var encodings = headers.allValues("Content-Encoding");
                if (encodings.size() > 1 || !encodings.isEmpty() && !"identity".equalsIgnoreCase(encodings.getFirst())) throw protocol();
            } catch (Exception exception) { body.completeExceptionally(protocol()); }
        }
        /** 返回仅在整体读取完成后可用的有界结果。 */
        @Override public CompletionStage<byte[]> getBody() { return body; }
        /** 无效响应头直接取消底层读取。 */
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null || body.isDone()) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        /** 每次分配前检查整个缓冲列表，失败时不保留超限部分。 */
        @Override public void onNext(List<ByteBuffer> incoming) {
            if (body.isDone()) return;
            long count = bytes.size();
            for (ByteBuffer buffer : incoming) count += buffer.remaining();
            if (count > NovelProviderResponseParser.MAXIMUM_BYTES) {
                subscription.cancel(); body.completeExceptionally(protocol()); return;
            }
            for (ByteBuffer buffer : incoming) {
                if (buffer.hasRemaining()) received.set(true);
                byte[] piece = new byte[buffer.remaining()];
                buffer.get(piece); bytes.writeBytes(piece);
            }
            subscription.request(1);
        }
        /** 底层异常不会携带外部正文离开客户端。 */
        @Override public void onError(Throwable throwable) { body.completeExceptionally(new java.io.IOException("Provider transport interrupted")); }
        /** 完整响应才能进入解析器。 */
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }

    private static String media(HttpHeaders headers) {
        var values = headers.allValues("Content-Type");
        if (values.size() != 1) throw protocol();
        String value = values.getFirst().toLowerCase(Locale.ROOT);
        if (!value.matches("(application/json|text/event-stream)(;\\s*charset=(utf-8|\"utf-8\"))?")) throw protocol();
        return value.split(";", 2)[0];
    }
    private static URI fixture(URI uri) {
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) throw protocol();
        return uri;
    }
    private static Integer optionalStatus(AtomicInteger status) { return status.get() == 0 ? null : status.get(); }

    /** 传输诊断只使用固定枚举，不改变未知调用禁止重发的业务分类。 */
    enum TransportFailure { CONNECT_TIMEOUT, TLS_FAILURE, RESPONSE_TIMEOUT, CONNECT_FAILURE, IO_FAILURE, OTHER }

    static TransportFailure transportFailure(Throwable exception) {
        TransportFailure fallback = TransportFailure.OTHER;
        for (int depth = 0; exception != null && depth < 8; depth++, exception = exception.getCause()) {
            // 连接超时是响应超时的子类，必须优先判定；有界异常链防止循环或无界诊断。
            if (exception instanceof java.net.http.HttpConnectTimeoutException) return TransportFailure.CONNECT_TIMEOUT;
            if (exception instanceof javax.net.ssl.SSLException) return TransportFailure.TLS_FAILURE;
            if (exception instanceof java.net.http.HttpTimeoutException) return TransportFailure.RESPONSE_TIMEOUT;
            if (exception instanceof java.net.ConnectException) return TransportFailure.CONNECT_FAILURE;
            if (exception instanceof java.io.IOException) fallback = TransportFailure.IO_FAILURE;
        }
        return fallback;
    }
    private static boolean authorized(BooleanSupplier supplier) {
        try { return supplier.getAsBoolean(); }
        catch (RuntimeException exception) { return false; }
    }
    private static NovelProviderException protocol() { return new NovelProviderException(ErrorCode.PROTOCOL); }
    private static boolean causeIsProtocol(Throwable exception) {
        for (int index = 0; exception != null && index < 8; index++, exception = exception.getCause()) {
            if (exception instanceof NovelProviderException provider && provider.error() == ErrorCode.PROTOCOL) return true;
        }
        return false;
    }
}
