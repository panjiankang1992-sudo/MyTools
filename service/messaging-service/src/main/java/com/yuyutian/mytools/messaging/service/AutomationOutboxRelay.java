package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

/**
 * 将入站消息 Outbox 可靠转发到自动化服务的轻量中继。
 */
@Component
public class AutomationOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutomationOutboxRelay.class);
    private static final int MAXIMUM_DELIVERY_ATTEMPTS = 10;
    private static final int MAXIMUM_PARALLEL_DELIVERIES = 4;
    private static final int MAXIMUM_RELAY_BATCH_SIZE = 200;
    private final MessagingRepository repository;
    private final MessagingProperties properties;
    private final RestClient restClient;
    private final ExecutorService deliveryExecutor = Executors.newFixedThreadPool(
            MAXIMUM_PARALLEL_DELIVERIES, runnable -> {
                Thread thread = new Thread(runnable, "automation-outbox-delivery");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 创建自动化 Outbox 中继。
     */
    public AutomationOutboxRelay(MessagingRepository repository, MessagingProperties properties,
                                 RestClient.Builder builder) {
        this.repository = repository;
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.automationUrl()).build();
    }

    /**
     * 分批转发尚未确认的入站消息事件。
     */
    @Scheduled(fixedDelayString = "${messaging.automation-relay-delay-ms:250}")
    public void relay() {
        if (!properties.automationRelayEnabled()) {
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
            List<MessagingRepository.OutboxEvent> events =
                    repository.claimUnpublishedInboundEvents(waveSize, claimLease);
            if (events.isEmpty()) {
                return;
            }
            try {
                // 大附件消息处理较慢时，保留有限并行通道让后续普通消息及时进入自动化。
                deliveryExecutor.invokeAll(events.stream().<java.util.concurrent.Callable<Void>>map(event -> () -> {
                    deliver(event);
                    return null;
                }).toList());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Automation outbox relay interrupted");
                return;
            }
            remaining -= events.size();
        }
    }

    private void deliver(MessagingRepository.OutboxEvent event) {
        try {
            restClient.post().uri("/internal/v1/message-events")
                    .header("Authorization", "Bearer " + properties.automationToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messageId", event.messageId().toString()))
                    .retrieve().toBodilessEntity();
            if (!repository.markOutboxPublished(event.id(), event.claimToken())) {
                LOGGER.warn("Automation outbox relay lost claim before publish: eventId={}", event.id());
            }
        } catch (RuntimeException exception) {
            // 单个毒事件采用有界退避，不能阻塞同批次后续的正常消息。
            MessagingRepository.InboundOutboxFailureOutcome outcome = repository.recordInboundOutboxFailure(
                    event.id(), event.claimToken(), MAXIMUM_DELIVERY_ATTEMPTS,
                    exception.getClass().getSimpleName());
            LOGGER.warn("Automation outbox relay failed: eventId={}, errorType={}, outcome={}",
                    event.id(), exception.getClass().getSimpleName(), outcome);
        }
    }

    /**
     * 停止并等待正在投递的入站事件。
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
