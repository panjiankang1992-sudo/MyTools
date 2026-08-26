package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.model.ChannelType;
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
    private final QQConnectorClient qqConnectorClient;
    private final DownloadIngestionClient downloadClient;

    /**
     * 创建自动化完成通知中继。
     *
     * @param repository 自动化仓储
     * @param properties 自动化配置
     * @param messagingClient 消息服务客户端
     */
    public CompletionOutboxRelay(AutomationRepository repository, AutomationProperties properties,
                                 MessagingClient messagingClient, QQConnectorClient qqConnectorClient,
                                 DownloadIngestionClient downloadClient) {
        this.repository = repository;
        this.properties = properties;
        this.messagingClient = messagingClient;
        this.qqConnectorClient = qqConnectorClient;
        this.downloadClient = downloadClient;
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
        relayEmail(limit);
        relayQq(limit);
    }

    private void relayEmail(int limit) {
        for (AutomationRepository.CompletionEvent event
                : repository.findUnpublishedCompletions(ChannelType.EMAIL, limit)) {
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

    private void relayQq(int limit) {
        for (AutomationRepository.CompletionEvent event
                : repository.findUnpublishedCompletions(ChannelType.QQ, limit)) {
            try {
                InboundMessage message = messagingClient.get(event.messageId());
                String prefix = message.externalMessageId().split(":", 3).length == 3
                        ? message.externalMessageId().split(":", 3)[2] : "";
                if (prefix.isBlank()) {
                    throw new IllegalStateException("QQ message id is missing");
                }
                String text = completionText(event, message);
                qqConnectorClient.send(message.sender(), prefix, text, 2);
                repository.markOutboxPublished(event.eventId());
            } catch (RuntimeException exception) {
                LOGGER.warn("QQ completion relay failed: eventId={}, errorType={}",
                        event.eventId(), exception.getClass().getSimpleName());
                break;
            }
        }
    }

    private String completionText(AutomationRepository.CompletionEvent event, InboundMessage message) {
        if (!"SUCCEEDED".equals(event.status())) {
            return "下载任务已结束，状态：" + event.status() + "。";
        }
        java.util.List<DownloadIngestionClient.DownloadItem> items = new java.util.ArrayList<>();
        for (AutomationRepository.ActionExecution action : repository.findActionExecutions(event.runId())) {
            java.util.UUID requestId = action.externalRequestId();
            if ("ATTACHMENT_DOWNLOAD".equals(action.actionType())) {
                MessagingClient.AttachmentSnapshot attachment = messagingClient.attachment(
                        action.externalRequestId(), message.ownerId());
                requestId = attachment.downloadRequestId();
            }
            if (requestId != null) {
                items.addAll(downloadClient.summary(requestId).items());
            }
        }
        if (items.isEmpty()) {
            return "下载处理已完成，共 " + event.actionCount() + " 个文件。";
        }
        boolean allTagged = items.stream().allMatch(item -> "TAGGED".equals(item.tagStatus()));
        StringBuilder text = new StringBuilder(allTagged ? "下载与标签已完成" : "下载处理已完成")
                .append("，共 ").append(items.size()).append(" 个文件：");
        for (int index = 0; index < items.size(); index++) {
            DownloadIngestionClient.DownloadItem item = items.get(index);
            text.append("\n\n").append(index + 1).append(". ").append(item.fileName())
                    .append("\n   标签：").append(formatTags(item));
        }
        return text.length() <= 1800 ? text.toString() : text.substring(0, 1797) + "...";
    }

    private String formatTags(DownloadIngestionClient.DownloadItem item) {
        if ("TAGGED".equals(item.tagStatus()) && !item.tags().isEmpty()) {
            return item.tags().stream().map(tag -> tag.name() + "（" + tag.type() + "，"
                    + String.format(java.util.Locale.ROOT, "%.2f", tag.confidence()) + "）")
                    .collect(java.util.stream.Collectors.joining("、"));
        }
        if ("SKIPPED".equals(item.tagStatus())) {
            return "已跳过（文件不支持）";
        }
        if ("FAILED".equals(item.tagStatus())) {
            return "失败（文件已保存）";
        }
        return "处理中";
    }
}
