package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.task.executor.client.ExecutorWorkloadTls;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 重启恢复专用客户端：没有 assertion registry，只能向配置的 Reader 原调用 PUT 结算。 */
public final class ReaderSettlementClient {
    private final URI origin;
    private final HttpClient http;
    private final String certificate;
    private final Clock clock;

    /** 只接宿主可信根地址与原证书，不从加密日志中的 URL 创建网络客户端。 */
    public ReaderSettlementClient(URI reader, ExecutorWorkloadTls tls) { this(reader, tls, Clock.systemUTC()); }

    ReaderSettlementClient(URI reader, ExecutorWorkloadTls tls, Clock clock) {
        this.origin = ReaderRelayEnvelope.root(reader);
        if (tls == null || tls.thumbprint() == null || clock == null) throw unavailable();
        this.http = tls.client(); this.certificate = tls.thumbprint(); this.clock = clock;
    }

    void settle(ReaderRelayEnvelope entry) {
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            if (entry == null || entry.terminal == null || !origin.equals(entry.origin) || !certificate.equals(entry.certificate)
                    || !clock.instant().isBefore(entry.expiresAt)) throw new ReaderAdaptationException(ErrorCode.FENCED, 0);
            Duration remaining = Duration.between(clock.instant(), entry.expiresAt);
            Duration timeout = remaining.compareTo(Duration.ofSeconds(10)) < 0 ? remaining : Duration.ofSeconds(10);
            byte[] body = NovelProviderJson.encode(NovelProviderJson.MAPPER.valueToTree(entry.terminal)).getBytes(StandardCharsets.UTF_8);
            if (body.length > 2097152) throw unavailable();
            URI target = origin.resolve("/api/internal/v1/chapter-adaptations/" + entry.adaptationId + "/executions/" + entry.executionId + "/attempts/" + entry.providerId);
            HttpRequest request = HttpRequest.newBuilder(target).timeout(timeout).header("Accept", "application/json")
                    .header("Accept-Encoding", "identity").header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Reader-Attempt-Settlement", entry.token).PUT(new NovelProviderClient.OncePublisher(body)).build();
            long started = System.nanoTime();
            future = http.sendAsync(request, info -> new ReaderResponseBody(info.headers(), 8192));
            while (true) {
                if (!clock.instant().isBefore(entry.expiresAt) || System.nanoTime() - started >= timeout.toNanos()) throw unavailable();
                try {
                    HttpResponse<byte[]> response = future.get(100, TimeUnit.MILLISECONDS);
                    var media = response.headers().allValues("Content-Type");
                    if (media.size() != 1 || !media.getFirst().toLowerCase(Locale.ROOT).matches("application/json(;\\s*charset=(utf-8|\"utf-8\"))?")) throw unavailable();
                    JsonNode result = NovelProviderJson.parse(NovelProviderJson.utf8(response.body()));
                    if (response.statusCode() != 200) throw new ReaderAdaptationException(ErrorCode.fromReader(result.path("code").asText()), response.statusCode());
                    if (result.size() != 5 || !entry.attemptId.toString().equals(result.path("attemptId").asText())
                            || !entry.providerId.toString().equals(result.path("providerAttemptId").asText())
                            || !entry.terminal.status().equals(result.path("status").asText()) || !result.path("archivedOnly").isBoolean()
                            || !result.path("terminalPayloadSha256").isTextual() || !result.get("terminalPayloadSha256").textValue().matches("[0-9a-f]{64}")) throw unavailable();
                    return;
                } catch (TimeoutException exception) {
                    // 只等待同一次 PUT，不自动重试；重放身份和退避由加密 relay 控制。
                }
            }
        } catch (ReaderAdaptationException exception) { throw exception; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception exception) { throw unavailable(); }
        finally { if (future != null && !future.isDone()) future.cancel(true); }
    }

    private static ReaderAdaptationException unavailable() { return new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 0); }
}
