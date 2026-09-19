package com.yuyutian.mytools.reader.utils.adaptation;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 在流式接收阶段执行字节上限，禁止先无限缓存响应再检查大小。 */
public final class BoundedByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final int maximumBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    /** 创建只适用于单次响应的有限订阅器。 */
    public BoundedByteArraySubscriber(int maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("Response limit must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    /** 返回完整响应或上限错误，不返回部分正文。 */
    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    /** 每次只请求一个批次，拒绝重复订阅。 */
    @Override
    public void onSubscribe(Flow.Subscription next) {
        if (subscription != null) {
            next.cancel();
            return;
        }
        subscription = next;
        subscription.request(1);
    }

    /** 在复制任一批次前计算总字节，超限立即取消传输。 */
    @Override
    public void onNext(List<ByteBuffer> items) {
        if (result.isDone()) {
            return;
        }
        long incoming = 0;
        for (ByteBuffer item : items) {
            incoming += item.remaining();
        }
        if (incoming > maximumBytes - buffer.size()) {
            // 不复制超限批次，也不向上层暴露已经收到的部分小说文本。
            result.completeExceptionally(new LimitExceededException());
            subscription.cancel();
            return;
        }
        for (ByteBuffer item : items) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            buffer.writeBytes(bytes);
        }
        subscription.request(1);
    }

    /** 保留传输失败的异常类型，由上层归一为稳定错误码。 */
    @Override
    public void onError(Throwable error) {
        result.completeExceptionally(error);
    }

    /** 仅在传输结束后一次性交付完整正文。 */
    @Override
    public void onComplete() {
        result.complete(buffer.toByteArray());
    }

    /** 不带响应正文的容量异常。 */
    public static final class LimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** 创建固定诊断，不携带路径、内容或外部错误。 */
        public LimitExceededException() {
            super("Response exceeds configured byte limit");
        }
    }
}
