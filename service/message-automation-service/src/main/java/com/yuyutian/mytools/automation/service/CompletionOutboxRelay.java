package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 将邮件渠道自动化终态可靠转交给 Messaging。
 */
@Component
public class CompletionOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompletionOutboxRelay.class);
    private final AutomationRepository repository;
    private final AutomationProperties properties;
    private final MessagingClient messagingClient;

    /**
     * 创建自动化完成通知中继。
     *
     * @param repository 自动化仓储
     * @param properties 自动化配置
     * @param messagingClient 消息服务客户端
     */
    public CompletionOutboxRelay(AutomationRepository repository, AutomationProperties properties,
                                 MessagingClient messagingClient) {
        this.repository = repository;
        this.properties = properties;
        this.messagingClient = messagingClient;
    }

    /**
     * 分批投递尚未获得 Messaging 确认的邮件完成事件。
     */
    @Scheduled(fixedDelayString = "${automation.completion-relay-delay-ms:2000}")
    public void relay() {
        if (!properties.completionRelayEnabled()) {
            return;
        }
        int limit = properties.completionRelayBatchSize() <= 0 ? 50 : properties.completionRelayBatchSize();
        for (AutomationRepository.CompletionEvent event : repository.findUnpublishedEmailCompletions(limit)) {
            try {
                InboundMessage message = messagingClient.get(event.messageId());
                messagingClient.createCompletionEmail(event.runId(), message.ownerId(), message.sender(),
                        event.status(), event.actionCount());
                repository.markOutboxPublished(event.eventId());
            } catch (RuntimeException exception) {
                // 保留未确认事件供下次重试，日志不包含收件地址、正文或鉴权信息。
                LOGGER.warn("Completion relay failed: eventId={}, errorType={}",
                        event.eventId(), exception.getClass().getSimpleName());
                break;
            }
        }
    }
}
