package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.ErrorCode;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 后台以持久租约和有界并发推进运行中的消息自动化状态。
 */
@Component
public class AutomationRunReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutomationRunReconciler.class);
    private final AutomationRepository repository;
    private final MessageAutomationService service;
    private final AutomationProperties properties;
    private final ExecutorService workerExecutor;
    private final Semaphore workerPermits;
    private final boolean ownsExecutor;

    /**
     * 创建自动化运行对账器。
     */
    @Autowired
    public AutomationRunReconciler(AutomationRepository repository, MessageAutomationService service,
                                   AutomationProperties properties) {
        this(repository, service, properties, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    AutomationRunReconciler(AutomationRepository repository, MessageAutomationService service,
                            AutomationProperties properties, ExecutorService workerExecutor) {
        this(repository, service, properties, workerExecutor, false);
    }

    private AutomationRunReconciler(AutomationRepository repository, MessageAutomationService service,
                                    AutomationProperties properties, ExecutorService workerExecutor,
                                    boolean ownsExecutor) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
        this.workerExecutor = workerExecutor;
        this.workerPermits = new Semaphore(properties.reconciliationConcurrency());
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * 非阻塞抢占当前有空闲 worker 的到期运行。
     */
    @Scheduled(fixedDelayString = "${automation.reconciliation-delay-ms:250}")
    public void reconcile() {
        int configuredBatch = Math.max(1, properties.reconciliationBatchSize());
        int reservationLimit = Math.min(configuredBatch, properties.reconciliationConcurrency());
        int reserved = reserveAvailableWorkers(reservationLimit);
        if (reserved == 0) {
            return;
        }

        List<AutomationRepository.RunClaim> claims;
        try {
            claims = repository.claimActiveRuns(reserved,
                    Duration.ofSeconds(properties.reconciliationLeaseSeconds()));
        } catch (RuntimeException exception) {
            workerPermits.release(reserved);
            LOGGER.warn("Automation run claim failed: errorType={}", exception.getClass().getSimpleName());
            return;
        }
        int unusedReservations = reserved - claims.size();
        if (unusedReservations > 0) {
            workerPermits.release(unusedReservations);
        }
        for (AutomationRepository.RunClaim claim : claims) {
            try {
                workerExecutor.execute(() -> reconcileClaim(claim));
            } catch (RuntimeException exception) {
                // executor 拒绝任务时立即持久化退避，不能把租约遗留到自然过期。
                recordFailure(claim, exception);
                workerPermits.release();
            }
        }
    }

    private int reserveAvailableWorkers(int limit) {
        int reserved = 0;
        while (reserved < limit && workerPermits.tryAcquire()) {
            reserved++;
        }
        return reserved;
    }

    private void reconcileClaim(AutomationRepository.RunClaim claim) {
        boolean succeeded = false;
        try {
            service.reconcileClaimed(claim);
            succeeded = true;
        } catch (RuntimeException exception) {
            recordFailure(claim, exception);
        } finally {
            try {
                if (succeeded) {
                    repository.releaseRunClaim(claim);
                }
            } catch (RuntimeException exception) {
                // 数据库暂时不可用时保留租约，随后由租约到期恢复，禁止无围栏立即重复执行。
                LOGGER.warn("Automation run claim release failed: runId={}, errorType={}",
                        claim.runId(), exception.getClass().getSimpleName());
            } finally {
                workerPermits.release();
            }
        }
    }

    private void recordFailure(AutomationRepository.RunClaim claim, RuntimeException exception) {
        boolean permanent = permanentFailure(exception);
        try {
            AutomationRepository.RunFailureOutcome outcome = repository.recordRunReconciliationFailure(
                    claim, properties.reconciliationMaxAttempts(), permanent,
                    ErrorCode.ACTION_STATUS_QUERY_FAILED.code(), exception.getClass().getSimpleName());
            LOGGER.warn("Automation reconciliation failed: runId={}, permanent={}, outcome={}, errorType={}",
                    claim.runId(), permanent, outcome, exception.getClass().getSimpleName());
        } catch (RuntimeException persistenceException) {
            // 失败状态无法落库时依赖租约到期恢复，避免错误释放导致热循环。
            LOGGER.warn("Automation reconciliation failure persistence failed: runId={}, errorType={}",
                    claim.runId(), persistenceException.getClass().getSimpleName());
        }
    }

    private boolean permanentFailure(RuntimeException exception) {
        if (exception instanceof RestClientResponseException response) {
            int statusCode = response.getStatusCode().value();
            return statusCode >= 400 && statusCode < 500
                    && statusCode != 408 && statusCode != 425 && statusCode != 429;
        }
        return exception instanceof IllegalArgumentException || exception instanceof IllegalStateException;
    }

    /**
     * 服务关闭时停止自有 worker，不接收新的后台工作。
     */
    @PreDestroy
    public void close() {
        if (ownsExecutor) {
            workerExecutor.shutdown();
        }
    }
}
