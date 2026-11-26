package com.yuyutian.mytools.messaging.utils.outbound;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 限制提供方响应大小，整体超时时可立即取消订阅并释放连接。 */
public final class LimitedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile Flow.Subscription subscription;
    /** 返回仅在全部有界正文接收完成后才结束的异步结果。 */
    @Override public CompletionStage<byte[]> getBody() { return result; }
    /** 注册网络背压订阅。 */
    @Override public void onSubscribe(Flow.Subscription value) { subscription = value; if (result.isDone()) value.cancel(); else value.request(1); }
    /** 超过八 KiB 立即停止读取，避免不可信响应持续占用内存。 */
    @Override public void onNext(List<ByteBuffer> chunks) {
        for (ByteBuffer chunk : chunks) {
            if (chunk.remaining() > 8192 - buffer.size()) { cancel(); return; }
            byte[] bytes = new byte[chunk.remaining()]; chunk.get(bytes); buffer.writeBytes(bytes);
        }
        subscription.request(1);
    }
    /** 网络失败只传递到内部结果，不直接返回客户端。 */
    @Override public void onError(Throwable error) { result.completeExceptionally(error); }
    /** 完整返回有界响应。 */
    @Override public void onComplete() { result.complete(buffer.toByteArray()); }
    /** 整体时间或大小预算耗尽后取消网络读取。 */
    public void cancel() {
        result.completeExceptionally(new IllegalStateException("Provider response budget exceeded"));
        Flow.Subscription value = subscription;
        if (value != null) value.cancel();
    }
}
