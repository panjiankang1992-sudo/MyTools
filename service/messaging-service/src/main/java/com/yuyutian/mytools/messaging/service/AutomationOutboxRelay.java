package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 将入站消息 Outbox 可靠转发到自动化服务的轻量中继。
 */
@Component
public class AutomationOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutomationOutboxRelay.class);
    private final MessagingRepository repository;
    private final MessagingProperties properties;
    private final RestClient restClient;

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
    @Scheduled(fixedDelayString = "${messaging.automation-relay-delay-ms:2000}")
    public void relay() {
        if (!properties.automationRelayEnabled()) {
            return;
        }
        int batchSize = properties.automationRelayBatchSize() <= 0 ? 50 : properties.automationRelayBatchSize();
        for (MessagingRepository.OutboxEvent event : repository.findUnpublishedInboundEvents(batchSize)) {
            try {
                restClient.post().uri("/internal/v1/message-events")
                        .header("Authorization", "Bearer " + properties.automationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("messageId", event.messageId().toString()))
                        .retrieve().toBodilessEntity();
                repository.markOutboxPublished(event.id());
            } catch (RuntimeException exception) {
                // 失败事件保留未发布状态，且日志不记录消息正文或鉴权信息。
                LOGGER.warn("Automation outbox relay failed: eventId={}, errorType={}",
                        event.id(), exception.getClass().getSimpleName());
                break;
            }
        }
    }
}
