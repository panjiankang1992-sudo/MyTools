package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 在入站事务完成后异步展开 OneBot 合并转发，并在最终化后发布消息事件。
 */
@Component
public class OneBotForwardExpansionRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OneBotForwardExpansionRelay.class);
    private static final int BATCH_SIZE = 8;
    private static final int PARALLEL_EXPANSIONS = 4;
    private static final int MAXIMUM_ATTEMPTS = 8;
    private static final long LEASE_SECONDS = 30L;
    private final MessagingRepository repository;
    private final OneBotInboundAdapter adapter;
    private final OneBotForwardExpansionClient client;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService expansionExecutor = Executors.newFixedThreadPool(
            PARALLEL_EXPANSIONS, runnable -> {
                Thread thread = new Thread(runnable, "onebot-forward-expansion");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 创建 OneBot 合并转发中继。
     *
     * @param repository 消息仓储
     * @param adapter OneBot 本地解析器
     * @param client OneBot 展开客户端
     * @param transactionTemplate 事务模板
     */
    public OneBotForwardExpansionRelay(MessagingRepository repository, OneBotInboundAdapter adapter,
                                       OneBotForwardExpansionClient client,
                                       TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.adapter = adapter;
        this.client = client;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 分批展开已经持久化的合并转发引用。
     */
    @Scheduled(fixedDelayString = "${messaging.onebot-forward-relay-delay-ms:500}")
    public void relay() {
        List<MessagingRepository.OneBotForwardExpansion> jobs =
                repository.findDueOneBotForwardExpansions(BATCH_SIZE);
        try {
            // 不同消息可并行展开；同一消息的最终化仍由数据库父记录锁串行化。
            expansionExecutor.invokeAll(jobs.stream().<java.util.concurrent.Callable<Void>>map(job -> () -> {
                expand(job);
                return null;
            }).toList());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("OneBot forward expansion relay interrupted");
        }
    }

    private void expand(MessagingRepository.OneBotForwardExpansion job) {
        UUID token = UUID.randomUUID();
        if (!repository.claimOneBotForwardExpansion(job.id(), token,
                Instant.now().plusSeconds(LEASE_SECONDS))) {
            return;
        }
        try {
            JsonNode messages = client.expand(job.accountKey(), job.forwardId());
            OneBotInboundAdapter.ParsedParts parsed = adapter.extractParts(messages, "", job.accountKey());
            Boolean completed = transactionTemplate.execute(status -> repository.completeOneBotForwardExpansion(
                    job, token, parsed.parts(), parsed.forwardIds(), parsed.truncated()));
            if (!Boolean.TRUE.equals(completed)) {
                LOGGER.warn("OneBot forward result lost its lease: jobId={}", job.id());
            }
        } catch (RuntimeException exception) {
            boolean retryable = retryable(exception);
            Boolean exhausted = transactionTemplate.execute(status -> repository.failOneBotForwardExpansion(
                    job, token, MAXIMUM_ATTEMPTS, retryable, exception.getClass().getSimpleName()));
            LOGGER.warn("OneBot forward expansion failed: jobId={}, errorType={}, retryable={}, exhausted={}",
                    job.id(), exception.getClass().getSimpleName(), retryable, Boolean.TRUE.equals(exhausted));
        }
    }

    private boolean retryable(RuntimeException exception) {
        if (!(exception instanceof HttpStatusCodeException statusException)) {
            return true;
        }
        HttpStatusCode status = statusException.getStatusCode();
        return status.is5xxServerError() || status.value() == 408 || status.value() == 429;
    }

    /**
     * 停止并等待正在执行的合并转发展开任务。
     */
    @PreDestroy
    public void close() {
        expansionExecutor.shutdown();
        try {
            if (!expansionExecutor.awaitTermination(20, TimeUnit.SECONDS)) {
                expansionExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            expansionExecutor.shutdownNow();
        }
    }
}
