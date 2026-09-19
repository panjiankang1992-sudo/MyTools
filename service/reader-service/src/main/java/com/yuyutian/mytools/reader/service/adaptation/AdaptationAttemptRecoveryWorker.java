package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.repository.adaptation.AdaptationAttemptRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 只做本地账本恢复，不访问 Provider、Scheduler 或凭据；暂停新建不能停用此清理入口。 */
@Component
@ConditionalOnProperty(prefix = "reader.adaptation-recovery", name = "enabled", havingValue = "true")
public class AdaptationAttemptRecoveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AdaptationAttemptRecoveryWorker.class);
    private final AdaptationAttemptRepository repository;
    private final ThreadPoolExecutor executor;
    private UUID scanAfter;

    /** 独立单线程且无内存队列，数据库故障不能阻塞派发或反复堆积扫描。 */
    public AdaptationAttemptRecoveryWorker(AdaptationAttemptRepository repository) {
        this.repository = repository;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "adaptation-attempt-recovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 每次唤醒最多提交一轮，已有工作未结束时不新增排队。 */
    @Scheduled(fixedDelayString = "${reader.adaptation-recovery.poll-ms:2000}")
    public void tick() {
        try {
            executor.execute(this::processBatch);
        } catch (RejectedExecutionException exception) {
            // 应用退出或上一轮仍运行时由持久状态保留后续恢复资格。
        }
    }

    /** 每个版本独立提交，单个版本故障不阻止同批其他范围恢复。 */
    public void processBatch() {
        if (executor.isShutdown()) return;
        try {
            var ids = repository.expiredAdaptationIds(scanAfter);
            // 扫描到末尾才环回，坏行和长时间被锁的范围不能永久挡住后面的身份。
            if (ids.isEmpty()) scanAfter = null;
            for (var id : ids) {
                // 关闭后不开始新的事务，已提交的归档不回退，也不触发模型调用。
                if (Thread.currentThread().isInterrupted() || executor.isShutdown()) return;
                try {
                    repository.recoverExpired(id);
                } catch (RuntimeException exception) {
                    LOG.warn("Adaptation attempt recovery unavailable");
                }
                scanAfter = id;
            }
        } catch (RuntimeException exception) {
            // 不记录可能含 SQL 参数、正文或密钥信息的底层异常链。
            LOG.warn("Adaptation attempt scan unavailable");
        }
    }

    /** 停止本机扫描线程，不撤销历史、不重置截止时间或预算。 */
    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
