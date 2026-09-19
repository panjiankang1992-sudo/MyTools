package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.config.ReaderAdaptationRecoverySchedulingConfiguration;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/** 独立恢复开关、无排队线程、故障隔离和停止行为不依赖外部网络。 */
class AdaptationAttemptRecoveryWorkerTest {
    @Test
    void shouldContinueOtherScopesWhenOneTransactionFails() {
        var repository = mock(AdaptationAttemptRepository.class);
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        when(repository.expiredAdaptationIds(null)).thenReturn(List.of(first, second));
        when(repository.recoverExpired(first)).thenThrow(new IllegalStateException("Fixture failure"));
        var worker = new AdaptationAttemptRecoveryWorker(repository);
        try {
            worker.processBatch();
            verify(repository).recoverExpired(first); verify(repository).recoverExpired(second);
        } finally { worker.close(); }
    }

    @Test
    void shouldContainDiscoveryFailureWithoutStartingRecovery() {
        var repository = mock(AdaptationAttemptRepository.class);
        when(repository.expiredAdaptationIds(null)).thenThrow(new IllegalStateException("Fixture failure"));
        var worker = new AdaptationAttemptRecoveryWorker(repository);
        try {
            assertThatCode(worker::processBatch).doesNotThrowAnyException();
            verify(repository, never()).recoverExpired(any());
        } finally { worker.close(); }
    }

    @Test
    void shouldNotBlockSchedulerOrEnqueueParallelScans() throws Exception {
        var repository = mock(AdaptationAttemptRepository.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(1);
        when(repository.expiredAdaptationIds(null)).thenAnswer(ignored -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); done.countDown(); return List.of();
        });
        var worker = new AdaptationAttemptRecoveryWorker(repository);
        try {
            worker.tick(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 20; index++) worker.tick();
            verify(repository, times(1)).expiredAdaptationIds(null);
            release.countDown(); assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); worker.close(); }
    }

    @Test
    void shouldNotStartAfterShutdown() {
        var repository = mock(AdaptationAttemptRepository.class);
        var worker = new AdaptationAttemptRecoveryWorker(repository);
        worker.close(); worker.tick(); worker.processBatch(); verifyNoInteractions(repository);
    }

    @Test
    void shouldAdvancePastFailingBatchAndWrapOnlyAfterEnd() {
        var repository = mock(AdaptationAttemptRepository.class);
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        when(repository.expiredAdaptationIds(null)).thenReturn(List.of(first));
        when(repository.expiredAdaptationIds(first)).thenReturn(List.of(second));
        when(repository.expiredAdaptationIds(second)).thenReturn(List.of());
        when(repository.recoverExpired(first)).thenThrow(new IllegalStateException("Fixture failure"));
        var worker = new AdaptationAttemptRecoveryWorker(repository);
        try {
            worker.processBatch(); worker.processBatch(); worker.processBatch(); worker.processBatch();
            verify(repository, times(2)).recoverExpired(first); verify(repository).recoverExpired(second);
            var order = inOrder(repository);
            order.verify(repository).expiredAdaptationIds(null); order.verify(repository).recoverExpired(first);
            order.verify(repository).expiredAdaptationIds(first); order.verify(repository).recoverExpired(second);
            order.verify(repository).expiredAdaptationIds(second); order.verify(repository).expiredAdaptationIds(null);
        } finally { worker.close(); }
    }

    @Test
    void shouldRequireOnlyIndependentRecoverySwitch() {
        var context = new ApplicationContextRunner()
                .withBean(AdaptationAttemptRepository.class, () -> mock(AdaptationAttemptRepository.class))
                .withUserConfiguration(ReaderAdaptationRecoverySchedulingConfiguration.class, AdaptationAttemptRecoveryWorker.class)
                .withPropertyValues("reader.adaptation.read-enabled=false", "reader.adaptation.create-enabled=false", "reader.adaptation-dispatch.enabled=false");
        context.run(value -> assertThat(value).doesNotHaveBean(AdaptationAttemptRecoveryWorker.class));
        context.withPropertyValues("reader.adaptation-recovery.enabled=true").run(value -> {
            assertThat(value).hasSingleBean(AdaptationAttemptRecoveryWorker.class);
            assertThat(value).hasSingleBean(ReaderAdaptationRecoverySchedulingConfiguration.class);
        });
    }
}
