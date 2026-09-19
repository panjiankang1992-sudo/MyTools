package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationRunReconcilerTest {

    @Test
    void shouldLetFastRunFinishWithoutWaitingForSlowRunOrBatchJoin() throws Exception {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessageAutomationService service = mock(MessageAutomationService.class);
        AutomationRepository.RunClaim slow = claim();
        AutomationRepository.RunClaim fast = claim();
        when(repository.claimActiveRuns(eq(2), any(Duration.class))).thenReturn(List.of(slow, fast));
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            slowStarted.countDown();
            if (!releaseSlow.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("slow worker timed out");
            }
            return null;
        }).when(service).reconcileClaimed(slow);
        doAnswer(invocation -> {
            fastFinished.countDown();
            return null;
        }).when(service).reconcileClaimed(fast);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AutomationRunReconciler reconciler = new AutomationRunReconciler(
                    repository, service, properties(2, 2), executor);
            long startedAt = System.nanoTime();
            reconciler.reconcile();

            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
            assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fastFinished.await(2, TimeUnit.SECONDS)).isTrue();
            verify(repository, timeout(2_000)).releaseRunClaim(fast);
            releaseSlow.countDown();
            verify(repository, timeout(2_000)).releaseRunClaim(slow);
        }
    }

    @Test
    void shouldReleasePermitAfterWorkerFailureAndAcceptNextRun() throws Exception {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessageAutomationService service = mock(MessageAutomationService.class);
        AutomationRepository.RunClaim failed = claim();
        AutomationRepository.RunClaim recovered = claim();
        when(repository.claimActiveRuns(eq(1), any(Duration.class)))
                .thenReturn(List.of(failed))
                .thenReturn(List.of(recovered));
        when(repository.recordRunReconciliationFailure(eq(failed), eq(3), eq(true),
                eq("AUTOMATION_006"), eq("IllegalStateException")))
                .thenReturn(AutomationRepository.RunFailureOutcome.TERMINATED);
        CountDownLatch recoveredFinished = new CountDownLatch(1);
        when(service.reconcileClaimed(failed)).thenThrow(new IllegalStateException("invalid message"));
        doAnswer(invocation -> {
            recoveredFinished.countDown();
            return null;
        }).when(service).reconcileClaimed(recovered);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            AutomationRunReconciler reconciler = new AutomationRunReconciler(
                    repository, service, properties(1, 1), executor);
            reconciler.reconcile();
            verify(repository, timeout(2_000)).recordRunReconciliationFailure(
                    failed, 3, true, "AUTOMATION_006", "IllegalStateException");

            reconciler.reconcile();

            assertThat(recoveredFinished.await(2, TimeUnit.SECONDS)).isTrue();
            verify(repository, timeout(2_000)).releaseRunClaim(recovered);
            verify(repository, never()).releaseRunClaim(failed);
            verify(repository, times(2)).claimActiveRuns(eq(1), any(Duration.class));
        }
    }

    private AutomationProperties properties(int batchSize, int concurrency) {
        return new AutomationProperties(null, null, null, null, null,
                false, 0, batchSize, 5, 25, concurrency, 30, 3);
    }

    private AutomationRepository.RunClaim claim() {
        return new AutomationRepository.RunClaim(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID().toString(), Instant.now().plusSeconds(30));
    }
}
