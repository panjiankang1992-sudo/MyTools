package com.yuyutian.mytools.reader.service.adaptation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 来源网络检查不能占住公共调度线程，也不能在内存累积重复领取。 */
class AdaptationSourceCheckWorkerTest {
    @Test
    void shouldReturnFromTickImmediatelyAndAllowOnlyOneRunningCheck() throws Exception {
        var service = mock(AdaptationSourceCheckService.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        when(service.processOne()).thenAnswer(ignored -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); finished.countDown(); return true;
        });
        var worker = new AdaptationSourceCheckWorker(service);
        try {
            worker.tick(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 20; index++) worker.tick();
            verify(service, times(1)).processOne();
            release.countDown(); assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); worker.close(); }
    }

    @Test
    void shouldNotAcceptMoreWorkAfterShutdown() {
        var service = mock(AdaptationSourceCheckService.class);
        var worker = new AdaptationSourceCheckWorker(service); worker.close(); worker.tick(); verifyNoInteractions(service);
    }
}
