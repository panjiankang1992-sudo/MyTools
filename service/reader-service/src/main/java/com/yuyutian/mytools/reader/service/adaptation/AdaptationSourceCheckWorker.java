package com.yuyutian.mytools.reader.service.adaptation;

import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 单独的一条有界取文线程，不让来源网络等待阻塞派发、取消及恢复的调度线程。 */
@Component
public class AdaptationSourceCheckWorker {
    private final AdaptationSourceCheckService service;
    private final ThreadPoolExecutor executor;

    /** 只允许一个运行任务且没有内存排队，持久队列由仓储统一限额。 */
    public AdaptationSourceCheckWorker(AdaptationSourceCheckService service) {
        this.service = service;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "adaptation-source-check"); thread.setDaemon(true); return thread;
        });
    }

    /** 计时器只提交一次短操作，上一轮运行时跳过本次唤醒，不重复领取。 */
    @Scheduled(fixedDelayString = "${reader.shelf-chapters.poll-ms:1500}")
    public void tick() {
        try {
            executor.execute(() -> {
                try { service.processOne(); }
                catch (RuntimeException exception) {
                    // 数据库故障不能使调度框架打印可能带正文的异常链，领取过期后可显式重试。
                    LoggerFactory.getLogger(AdaptationSourceCheckWorker.class).warn("Adaptation source check unavailable");
                }
            });
        } catch (RejectedExecutionException exception) {
            // 单任务正在执行或应用正在关闭时不在内存排队。
        }
    }

    /** 关闭时中断本地观察，未完成的持久核验租约自行过期，不伪造 CURRENT。 */
    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
