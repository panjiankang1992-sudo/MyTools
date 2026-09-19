package com.yuyutian.mytools.task.executor.client.adaptation;

import com.yuyutian.mytools.task.executor.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NovelAdaptationRunTest {
    private final ReaderAdaptationClient reader = mock(ReaderAdaptationClient.class);
    private final NovelAdaptationWorkflow workflow = mock(NovelAdaptationWorkflow.class);
    private final MutableClock clock = new MutableClock();
    private final AtomicBoolean allowed = new AtomicBoolean(true);

    @Test
    void waitsAndOnlyCompletesAfterWorkflowConfirmsReaderCompletion() {
        when(workflow.advance()).thenReturn(step(NovelAdaptationWorkflow.Action.PROGRESSED), step(NovelAdaptationWorkflow.Action.WAITING), step(NovelAdaptationWorkflow.Action.COMPLETED));
        var result = run(10).execute();
        assertThat(result.status()).isEqualTo("SUCCEEDED"); assertThat(result.errorCode()).isNull();
        assertThat(clock.millis.get()).isEqualTo(1000); verify(workflow, times(3)).advance(); verify(workflow).close();
        verify(reader, never()).fail(any());
    }

    @Test
    void retriesSameWorkflowForTransientAuthorityFailureWithoutExtendingDeadline() {
        when(workflow.advance()).thenThrow(new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 503));
        assertThat(run(2).execute().status()).isEqualTo("TIMED_OUT");
        assertThat(clock.millis.get()).isEqualTo(2000); verify(workflow, times(3)).advance(); verify(workflow).close();
        verify(reader, never()).fail(any());
    }

    @Test
    void taskBindingPendingIsRetryableAndDoesNotCreateAnotherWorkflow() {
        when(workflow.advance()).thenThrow(new ReaderAdaptationException(ErrorCode.BIND_PENDING, 409)).thenReturn(step(NovelAdaptationWorkflow.Action.COMPLETED));
        assertThat(run(10).execute().status()).isEqualTo("SUCCEEDED"); verify(workflow, times(2)).advance();
        assertThat(clock.millis.get()).isEqualTo(500);
    }

    @Test
    void cancellationDuringWaitClosesWorkflowAndLeavesNoBusinessFailureWrite() {
        when(workflow.advance()).thenReturn(step(NovelAdaptationWorkflow.Action.WAITING));
        var run = new NovelAdaptationRun(reader, workflow, clock.instant().plusSeconds(10), allowed::get, clock,
                milliseconds -> { clock.millis.addAndGet(milliseconds); allowed.set(false); });
        assertThat(run.execute().status()).isEqualTo("CANCELLED"); verify(workflow).close(); verify(reader, never()).fail(any());
    }

    @Test
    void knownLocalFailureUsesFixedReaderCodeAndNeverIncludesDiagnosticText() {
        when(reader.active()).thenReturn(true);
        when(workflow.advance()).thenThrow(new NovelProviderException(ErrorCode.CONTEXT));
        var result = run(10).execute(); assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.errorCode()).isEqualTo(ErrorCode.CONTEXT);
        verify(reader).fail(ErrorCode.CONTEXT); verify(workflow).close();
    }

    @Test
    void storageFailureDoesNotAttemptInvalidReaderBusinessFailure() {
        when(workflow.advance()).thenThrow(new ReaderAdaptationException(ErrorCode.PERSISTENCE_UNAVAILABLE, 0));
        var result = run(10).execute(); assertThat(result.errorCode()).isEqualTo(ErrorCode.PERSISTENCE_UNAVAILABLE);
        verify(reader, never()).fail(any()); verify(workflow).close();
    }

    @Test
    void unexpectedFailureIsRedactedAndDeadlineCannotBeExtended() {
        when(workflow.advance()).thenThrow(new IllegalStateException("fixture-private-path-and-body"));
        assertThat(run(10).execute()).isEqualTo(new NovelAdaptationRun.Result("FAILED", ErrorCode.UNKNOWN));
        verify(workflow).close();
        assertThatThrownBy(() -> run(901)).hasMessage(ErrorCode.CONTEXT.code());
    }

    @Test
    void interruptionIsPreservedAndReleasesRecoveryOwnership() {
        when(workflow.advance()).thenReturn(step(NovelAdaptationWorkflow.Action.WAITING));
        try {
            var run = new NovelAdaptationRun(reader, workflow, clock.instant().plusSeconds(10), allowed::get, clock, milliseconds -> { throw new InterruptedException(); });
            assertThat(run.execute().status()).isEqualTo("CANCELLED"); assertThat(Thread.currentThread().isInterrupted()).isTrue(); verify(workflow).close();
        } finally { Thread.interrupted(); }
    }

    private NovelAdaptationRun run(int seconds) { return new NovelAdaptationRun(reader, workflow, clock.instant().plusSeconds(seconds), allowed::get, clock, clock.millis::addAndGet); }
    private static NovelAdaptationWorkflow.Advance step(NovelAdaptationWorkflow.Action action) { return new NovelAdaptationWorkflow.Advance(action, null); }
    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong();
        /** 固定测试时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 测试不改变时区。 */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 推进受控时钟，避免真实等待任务期限。 */
        @Override public Instant instant() { return Instant.parse("2026-09-10T10:00:00Z").plusMillis(millis.get()); }
    }
}
