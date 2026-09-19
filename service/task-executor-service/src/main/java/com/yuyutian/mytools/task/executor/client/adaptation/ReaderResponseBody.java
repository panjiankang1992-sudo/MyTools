package com.yuyutian.mytools.task.executor.client.adaptation;

import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Reader 有界响应订阅，拒绝压缩和超限，不在读取结束后才检查正文大小。 */
final class ReaderResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final int maximum;
    private Flow.Subscription subscription;

    ReaderResponseBody(HttpHeaders headers, int maximum) {
        this.maximum = maximum;
        try {
            long headerBytes = 0;
            for (var entry : headers.map().entrySet()) {
                headerBytes += entry.getKey().length();
                for (String value : entry.getValue()) headerBytes += value.length();
            }
            var encoding = headers.allValues("Content-Encoding");
            var length = headers.allValues("Content-Length");
            if (headerBytes > 8192 || headers.map().size() > 64 || encoding.size() > 1
                    || !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding.getFirst()) || length.size() > 1
                    || !length.isEmpty() && (!length.getFirst().matches("[0-9]{1,9}") || Long.parseLong(length.getFirst()) > maximum)) throw invalid();
        } catch (Exception exception) { result.completeExceptionally(invalid()); }
    }

    /** 完整读取后才返回字节，不提供可提前消费的流。 */
    @Override public CompletionStage<byte[]> getBody() { return result; }
    /** 无效响应头或重复订阅立即取消。 */
    @Override public void onSubscribe(Flow.Subscription value) {
        if (subscription != null || result.isDone()) { value.cancel(); return; }
        subscription = value; value.request(1);
    }
    /** 复制前检查总量，超限不保留超出部分。 */
    @Override public void onNext(List<ByteBuffer> incoming) {
        if (result.isDone()) return;
        long length = body.size();
        for (ByteBuffer buffer : incoming) length += buffer.remaining();
        if (length > maximum) { subscription.cancel(); result.completeExceptionally(invalid()); return; }
        for (ByteBuffer buffer : incoming) {
            byte[] piece = new byte[buffer.remaining()]; buffer.get(piece); body.writeBytes(piece);
        }
        subscription.request(1);
    }
    /** 不向上层传递可能包含端点或请求片段的异常链。 */
    @Override public void onError(Throwable ignored) { result.completeExceptionally(new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 0)); }
    /** 结束后交给严格 UTF-8 与 JSON 解析。 */
    @Override public void onComplete() { result.complete(body.toByteArray()); }
    private static ReaderAdaptationException invalid() { return new ReaderAdaptationException(ErrorCode.CONTEXT, 0); }
}
