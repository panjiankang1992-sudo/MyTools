package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.CreateInboundReplyRequest;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 将已持久化的 OneBot 合并转发受理事件可靠回复到原会话。
 */
@Component
public class OneBotAcceptanceOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OneBotAcceptanceOutboxRelay.class);
    private static final int MAXIMUM_DELIVERY_ATTEMPTS = 10;
    private static final int MAXIMUM_PARALLEL_DELIVERIES = 4;
    private static final int MAXIMUM_RELAY_BATCH_SIZE = 200;
    private final MessagingRepository repository;
    private final InboundReplyService replyService;
    private final MessagingProperties properties;
    private final ExecutorService deliveryExecutor = Executors.newFixedThreadPool(
            MAXIMUM_PARALLEL_DELIVERIES, runnable -> {
                Thread thread = new Thread(runnable, "onebot-acceptance-delivery");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 创建 OneBot 受理回复中继。
     *
     * @param repository 消息仓储
     * @param replyService 原会话回复服务
     * @param properties 消息配置
     */
    public OneBotAcceptanceOutboxRelay(MessagingRepository repository, InboundReplyService replyService,
                                       MessagingProperties properties) {
        this.repository = repository;
        this.replyService = replyService;
        this.properties = properties;
    }

    /**
     * 并行投递一批尚未确认的受理回复。
     */
    @Scheduled(fixedDelayString = "${messaging.onebot-acceptance-relay-delay-ms:250}")
    public void relay() {
        if (!properties.oneBotIngressEnabled()) {
            return;
        }
        int configuredBatchSize = properties.automationRelayBatchSize() <= 0
                ? 50 : properties.automationRelayBatchSize();
        int batchSize = Math.min(configuredBatchSize, MAXIMUM_RELAY_BATCH_SIZE);
        Duration claimLease = properties.inboundOutboxClaimLease();
        int remaining = batchSize;
        while (remaining > 0) {
            // 每一波只领取能立即执行的事件，避免租约在本机执行队列中提前耗尽。
            int waveSize = Math.min(remaining, MAXIMUM_PARALLEL_DELIVERIES);
            List<MessagingRepository.OneBotAcceptanceEvent> events =
                    repository.claimUnpublishedOneBotAcceptanceEvents(waveSize, claimLease);
            if (events.isEmpty()) {
                return;
            }
            try {
                deliveryExecutor.invokeAll(events.stream().<java.util.concurrent.Callable<Void>>map(event -> () -> {
                    deliver(event);
                    return null;
                }).toList());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.warn("OneBot acceptance outbox relay interrupted");
                return;
            }
            remaining -= events.size();
        }
    }

    private void deliver(MessagingRepository.OneBotAcceptanceEvent event) {
        if (!event.valid()) {
            MessagingRepository.InboundOutboxFailureOutcome outcome = repository.recordInboundOutboxFailure(
                    event.id(), event.claimToken(), 1, "InvalidAcceptancePayload");
            LOGGER.warn("OneBot acceptance relay rejected invalid payload: eventId={}, outcome={}",
                    event.id(), outcome);
            return;
        }
        try {
            replyService.reply(event.messageId(), new CreateInboundReplyRequest(
                    event.idempotencyKey(), event.body()));
            if (!repository.markOutboxPublished(event.id(), event.claimToken())) {
                LOGGER.warn("OneBot acceptance relay lost claim before publish: eventId={}", event.id());
            }
        } catch (RuntimeException exception) {
            // 明确拒绝不会通过重试恢复，其余瞬时故障采用有界退避并保留死信供人工重驱。
            int maximumAttempts = exception instanceof InboundReplyRejectedException
                    ? 1 : MAXIMUM_DELIVERY_ATTEMPTS;
            MessagingRepository.InboundOutboxFailureOutcome outcome = repository.recordInboundOutboxFailure(
                    event.id(), event.claimToken(), maximumAttempts, exception.getClass().getSimpleName());
            LOGGER.warn("OneBot acceptance relay failed: eventId={}, errorType={}, outcome={}",
                    event.id(), exception.getClass().getSimpleName(), outcome);
        }
    }

    /**
     * 停止并等待正在投递的受理回复。
     */
    @PreDestroy
    public void close() {
        deliveryExecutor.shutdown();
        try {
            if (!deliveryExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                deliveryExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deliveryExecutor.shutdownNow();
        }
    }
}
