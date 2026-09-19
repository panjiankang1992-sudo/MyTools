package com.yuyutian.mytools.reader.utils.adaptation;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedByteArraySubscriberTest {

    @Test
    void shouldCollectOnlyCompleteBodyWithinLimit() {
        var body = new BoundedByteArraySubscriber(5);
        var subscription = new TestSubscription();
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap("ab".getBytes(StandardCharsets.UTF_8))));
        body.onNext(List.of(ByteBuffer.wrap("cde".getBytes(StandardCharsets.UTF_8))));
        assertThat(body.getBody().toCompletableFuture()).isNotDone();
        body.onComplete();
        assertThat(body.getBody().toCompletableFuture().join()).isEqualTo("abcde".getBytes(StandardCharsets.UTF_8));
        assertThat(subscription.cancelled).isFalse();
        assertThat(subscription.requested).isEqualTo(3);
    }

    @Test
    void shouldCancelBeforeCopyingOversizedBatchAndNeverReturnPartialText() {
        var body = new BoundedByteArraySubscriber(4);
        var subscription = new TestSubscription();
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[3])));
        ByteBuffer oversize = ByteBuffer.wrap(new byte[2]);
        body.onNext(List.of(oversize));
        body.onComplete();
        assertThat(subscription.cancelled).isTrue();
        assertThat(oversize.position()).isZero();
        assertThatThrownBy(() -> body.getBody().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(BoundedByteArraySubscriber.LimitExceededException.class);
    }

    @Test
    void shouldCancelDuplicateSubscriptionAndKeepOriginalFailure() {
        var body = new BoundedByteArraySubscriber(4);
        var first = new TestSubscription();
        var duplicate = new TestSubscription();
        body.onSubscribe(first);
        body.onSubscribe(duplicate);
        assertThat(first.cancelled).isFalse();
        assertThat(duplicate.cancelled).isTrue();
        body.onError(new IllegalStateException("fixture-failure"));
        body.onComplete();
        assertThatThrownBy(() -> body.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private static final class TestSubscription implements Flow.Subscription {
        private boolean cancelled;
        private long requested;

        /** 记录有限背压请求。 */
        @Override
        public void request(long count) {
            requested += count;
        }

        /** 记录取消，不产生真实网络访问。 */
        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
