package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 将所有渠道的自动化终态可靠转交给 Messaging。
 */
@Component
public class CompletionOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompletionOutboxRelay.class);
    private static final int COMPLETION_PAGE_CONTENT_LIMIT = 1700;
    private static final int MAXIMUM_QQ_COMPLETION_PAGES = 8;
    private static final String QQ_COMPLETION_TRUNCATION_NOTICE =
            "\n\n结果超过 8 页，后续内容已截断；请在系统中查询完整结果。";
    private static final java.util.Set<String> TERMINAL_DOWNLOAD_STATUSES =
            java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final java.util.Set<String> TERMINAL_TAG_STATUSES =
            java.util.Set.of("TAGGED", "FAILED", "SKIPPED");
    private static final int MAXIMUM_EXTERNAL_CONCURRENCY = 4;
    private static final long EVENT_DEADLINE_SECONDS = 120L;
    private static final long DEFAULT_DEFER_SECONDS = 1L;
    private static final long MAXIMUM_DEFER_SECONDS = 60L;
    private final AutomationRepository repository;
    private final AutomationProperties properties;
    private final MessagingClient messagingClient;
    private final DownloadIngestionClient downloadClient;

    /**
     * 创建自动化完成通知中继。
     *
     * @param repository 自动化仓储
     * @param properties 自动化配置
     * @param messagingClient 消息服务客户端
     */
    public CompletionOutboxRelay(AutomationRepository repository, AutomationProperties properties,
                                 MessagingClient messagingClient,
                                 DownloadIngestionClient downloadClient) {
        this.repository = repository;
        this.properties = properties;
        this.messagingClient = messagingClient;
        this.downloadClient = downloadClient;
    }

    /**
     * 分批投递尚未获得 Messaging 确认的邮件完成事件。
     */
    @Scheduled(fixedDelayString = "${automation.completion-relay-delay-ms:250}")
    public void relay() {
        if (!properties.completionRelayEnabled()) {
            return;
        }
        int limit = properties.completionRelayBatchSize() <= 0 ? 50 : properties.completionRelayBatchSize();
        relayBatch(limit);
    }

    private void relayBatch(int limit) {
        // 事件期限从真正开始投递时计算；认领量不得超过共享下游许可，避免尾部事件仅因排队超时。
        int claimLimit = Math.min(Math.max(limit, 0), MAXIMUM_EXTERNAL_CONCURRENCY);
        if (claimLimit == 0) {
            return;
        }
        java.util.List<AutomationRepository.CompletionEvent> events =
                repository.claimUnpublishedCompletions(claimLimit);
        if (events.isEmpty()) {
            return;
        }
        int perEventConcurrency = Math.max(
                1, MAXIMUM_EXTERNAL_CONCURRENCY / events.size());
        // 全批次共享同一许可池，避免每个事件各自放大到总并发超过下游承载上限。
        java.util.concurrent.Semaphore externalPermits =
                new java.util.concurrent.Semaphore(MAXIMUM_EXTERNAL_CONCURRENCY, true);
        try (java.util.concurrent.ExecutorService executor =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<java.util.concurrent.CompletableFuture<Void>> deliveries = events.stream()
                    .map(event -> java.util.concurrent.CompletableFuture.runAsync(
                            () -> relayEvent(event, executor,
                                    externalPermits,
                                    perEventConcurrency), executor))
                    .toList();
            java.util.concurrent.CompletableFuture.allOf(
                    deliveries.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        }
    }

    private void relayEvent(AutomationRepository.CompletionEvent event,
                            java.util.concurrent.ExecutorService executor,
                            java.util.concurrent.Semaphore externalPermits,
                            int actionWindowSize) {
        try {
            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(EVENT_DEADLINE_SECONDS);
            if (event.duplicateOnly()) {
                // 专用事件不需要重新读取消息或链接，且与同步快路径共用同一幂等请求。
                externalCall(() -> messagingClient.reply(event.messageId(),
                        DuplicateLinkFeedback.idempotencyKey(event.runId()),
                        DuplicateLinkFeedback.body()), externalPermits, deadline);
                publish(event);
                return;
            }
            InboundMessage message = externalCall(
                    () -> messagingClient.get(event.messageId()), externalPermits, deadline);
            java.util.List<String> pages = channelPages(message.channelType(), completionTexts(
                    event, message, executor, externalPermits, deadline, actionWindowSize));
            if (event.deliveryPageCursor() > pages.size()) {
                throw new IllegalStateException("Completion delivery page cursor exceeds page count");
            }
            // 最后一页确认后若发布提交失败，下一次领取只补做发布，不重复回复。
            if (event.deliveryPageCursor() == pages.size()) {
                publish(event);
                return;
            }
            for (int index = event.deliveryPageCursor(); index < pages.size(); index++) {
                int page = index;
                externalCall(() -> replyPage(event, message, pages, page),
                        externalPermits, deadline);
                if (!acknowledgePage(event, page)) {
                    // 回复已受理但租约失效时立即停止，稳定幂等键允许新 worker 安全补偿。
                    LOGGER.warn("Completion relay lost claim before page acknowledgement: "
                            + "eventId={}, page={}", event.eventId(), page + 1);
                    return;
                }
            }
            publish(event);
        } catch (HttpClientErrorException exception) {
            String errorType = exception.getClass().getSimpleName();
            int statusCode = exception.getStatusCode().value();
            if (statusCode == 425) {
                // QQ 出站 WAL 尚在租约或退避窗口时，仅延后外层事件，不消耗投递预算。
                boolean deferred = repository.deferOutboxDelivery(
                        event.eventId(), event.claimToken(), retryAfter(exception), errorType);
                LOGGER.info("Completion relay deferred by downstream lease: eventId={}, deferred={}",
                        event.eventId(), deferred);
            } else if (statusCode == 400 || statusCode == 404
                    || statusCode == 409 || statusCode == 422) {
                // 历史消息已永久失效，转死信以免阻塞后续完成通知。
                repository.markOutboxDead(event.eventId(), event.claimToken(), errorType);
                LOGGER.warn("Completion relay marked permanent event dead: eventId={}, errorType={}",
                        event.eventId(), errorType);
            } else {
                recordFailure(event, errorType);
            }
        } catch (RuntimeException exception) {
            // 临时故障只进行有限次数重试，避免历史毒事件永久占用队列。
            recordFailure(event, exception.getClass().getSimpleName());
        }
    }

    private Duration retryAfter(HttpClientErrorException exception) {
        String header = exception.getResponseHeaders() == null
                ? null : exception.getResponseHeaders().getFirst("Retry-After");
        long requestedSeconds = DEFAULT_DEFER_SECONDS;
        if (header != null) {
            try {
                requestedSeconds = Long.parseLong(header.strip());
            } catch (NumberFormatException ignored) {
                // 非法或非秒数形式使用短暂默认退避，仓储层再次执行边界约束。
            }
        }
        return Duration.ofSeconds(Math.max(1L,
                Math.min(requestedSeconds, MAXIMUM_DEFER_SECONDS)));
    }

    private void publish(AutomationRepository.CompletionEvent event) {
        if (!repository.markOutboxPublished(event.eventId(), event.claimToken())) {
            // 租约已过期或被新 worker 接管时，旧 worker 不得覆盖新拥有者的结果。
            LOGGER.warn("Completion relay lost claim before publish: eventId={}", event.eventId());
        }
    }

    private MessagingClient.InboundReplySnapshot replyPage(
            AutomationRepository.CompletionEvent event, InboundMessage message,
            java.util.List<String> pages, int page) {
        if (message.channelType() == ChannelType.QQ
                || pages.size() > 1) {
            // QQ Connector 将 page-1..8 确定性映射到原消息的被动回复序号。
            return messagingClient.reply(event.messageId(),
                    "automation-completion-" + event.runId() + "-page-" + (page + 1),
                    pages.get(page));
        }
        return messagingClient.reply(event.messageId(), event.runId(), pages.get(page));
    }

    private boolean acknowledgePage(AutomationRepository.CompletionEvent event, int expectedCursor) {
        if (event.claimToken() == null) {
            // 仅保留给只读/单元测试构造器；生产 relay 的事件始终来自带租约的 claim 查询。
            return true;
        }
        return repository.advanceOutboxDeliveryPage(
                event.eventId(), event.claimToken(), expectedCursor);
    }

    private java.util.List<String> channelPages(
            ChannelType channelType,
            java.util.List<String> pages) {
        if (channelType != ChannelType.QQ
                || pages.size() <= MAXIMUM_QQ_COMPLETION_PAGES) {
            return pages;
        }
        java.util.List<String> bounded = new java.util.ArrayList<>(MAXIMUM_QQ_COMPLETION_PAGES);
        bounded.addAll(pages.subList(0, MAXIMUM_QQ_COMPLETION_PAGES - 1));
        String finalPage = pages.get(MAXIMUM_QQ_COMPLETION_PAGES - 1);
        int finalPageLimit = COMPLETION_PAGE_CONTENT_LIMIT
                - QQ_COMPLETION_TRUNCATION_NOTICE.length();
        if (finalPage.length() > finalPageLimit) {
            int end = finalPageLimit - 3;
            if (end > 0 && Character.isHighSurrogate(finalPage.charAt(end - 1))) {
                end--;
            }
            finalPage = finalPage.substring(0, end) + "...";
        }
        bounded.add(finalPage + QQ_COMPLETION_TRUNCATION_NOTICE);
        return java.util.List.copyOf(bounded);
    }

    private void recordFailure(AutomationRepository.CompletionEvent event, String errorType) {
        boolean exhausted = repository.recordOutboxFailure(
                event.eventId(), event.claimToken(),
                properties.completionRelayMaxAttempts(), errorType);
        LOGGER.warn("Completion relay failed: eventId={}, errorType={}, exhausted={}",
                event.eventId(), errorType, exhausted);
    }

    private java.util.List<String> completionTexts(
            AutomationRepository.CompletionEvent event, InboundMessage message,
            java.util.concurrent.ExecutorService executor,
            java.util.concurrent.Semaphore externalPermits, long deadline,
            int actionWindowSize) {
        java.util.List<DownloadIngestionClient.DownloadItem> items = new java.util.ArrayList<>();
        java.util.List<String> resolvedStatuses = new java.util.ArrayList<>();
        java.util.List<AutomationRepository.ActionExecution> actions =
                repository.findActionExecutions(event.runId());
        for (int offset = 0; offset < actions.size(); offset += actionWindowSize) {
            int end = Math.min(offset + actionWindowSize, actions.size());
            java.util.List<java.util.concurrent.CompletableFuture<
                    ActionCompletion>> summaries =
                    actions.subList(offset, end).stream()
                            .map(action -> java.util.concurrent.CompletableFuture.supplyAsync(
                                    () -> completionItems(
                                            action, message, externalPermits, deadline), executor))
                            .toList();
            try {
                java.util.concurrent.CompletableFuture.allOf(
                        summaries.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
                for (java.util.concurrent.CompletableFuture<
                        ActionCompletion> summary : summaries) {
                    // future 列表保持动作顺序，因此并发查询不会改变最终附件顺序。
                    ActionCompletion completion = summary.join();
                    resolvedStatuses.add(completion.status());
                    items.addAll(completion.items());
                }
            } catch (java.util.concurrent.CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw exception;
            }
        }
        String resolvedStatus = resolvedCompletionStatus(event.status(), resolvedStatuses);
        if (items.isEmpty()) {
            if ("NO_MATCH".equals(event.status())) {
                return java.util.List.of("未匹配到下载规则或消息中没有可处理内容，本次未创建下载任务。");
            }
            if ("SUCCEEDED".equals(resolvedStatus) && !actions.isEmpty()) {
                // 下载任务可能仅命中全局去重记录；这是有效终态，不应重试直至死信。
                return java.util.List.of("处理完成，没有新增文件（内容可能已处理）。");
            }
            String state = "SUCCEEDED".equals(resolvedStatus)
                    ? "已完成" : "已结束，状态：" + resolvedStatus;
            return java.util.List.of("下载处理" + state + "，共 " + event.actionCount() + " 个文件。");
        }
        boolean allTagged = items.stream().allMatch(item -> "TAGGED".equals(item.tagStatus()));
        String title = switch (resolvedStatus) {
            case "PARTIAL_FAILED" -> "下载处理部分失败";
            case "FAILED" -> "下载处理失败";
            case "CANCELLED" -> "下载处理已取消";
            default -> allTagged ? "下载与标签已完成" : "下载处理已完成";
        };
        java.util.List<String> contents = new java.util.ArrayList<>();
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            DownloadIngestionClient.DownloadItem item = items.get(index);
            String entry = "\n\n" + (index + 1) + ". " + item.fileName()
                    + "\n   标签：" + formatTags(item);
            if (entry.length() > COMPLETION_PAGE_CONTENT_LIMIT) {
                entry = entry.substring(0, COMPLETION_PAGE_CONTENT_LIMIT - 3) + "...";
            }
            if (!content.isEmpty() && content.length() + entry.length() > COMPLETION_PAGE_CONTENT_LIMIT) {
                contents.add(content.toString());
                content = new StringBuilder();
            }
            content.append(entry);
        }
        if (!content.isEmpty()) {
            contents.add(content.toString());
        }
        if (contents.size() == 1) {
            return java.util.List.of(title + "，共 " + items.size() + " 个文件：" + contents.getFirst());
        }
        java.util.List<String> pages = new java.util.ArrayList<>();
        for (int index = 0; index < contents.size(); index++) {
            pages.add(title + "，共 " + items.size() + " 个文件（" + (index + 1) + "/"
                    + contents.size() + "）：" + contents.get(index));
        }
        return java.util.List.copyOf(pages);
    }

    private ActionCompletion completionItems(
            AutomationRepository.ActionExecution action, InboundMessage message,
            java.util.concurrent.Semaphore externalPermits, long deadline) {
        try {
            java.util.UUID requestId = action.externalRequestId();
            if (requestId != null && "ATTACHMENT_DOWNLOAD".equals(action.actionType())) {
                MessagingClient.AttachmentSnapshot attachment = externalCall(
                        () -> messagingClient.attachment(action.externalRequestId(), message.ownerId()),
                        externalPermits, deadline);
                requestId = attachment.downloadRequestId();
            }
            if (requestId != null) {
                java.util.UUID resolvedRequestId = requestId;
                DownloadIngestionClient.DownloadSummary summary = externalCall(
                        () -> downloadClient.summary(resolvedRequestId, message.ownerId()),
                        externalPermits, deadline);
                if (!TERMINAL_DOWNLOAD_STATUSES.contains(summary.status())) {
                    if (terminalFailureAction(action)) {
                        // 本地终态来自有界对账预算时，下游可能仍在执行；不得因此毒化完成事件。
                        return fallbackCompletion(action);
                    }
                    throw new IllegalStateException("Download result summary is not terminal");
                }
                requireUsableSummary(action, summary);
                if (summary.items().isEmpty()
                        && ("FAILED".equals(summary.status()) || "CANCELLED".equals(summary.status()))) {
                    // 下游失败或取消且没有已保存文件时，使用计划阶段元数据解释真实终态。
                    return fallbackCompletion(action, summary.status());
                }
                // 空列表表示任务成功但所有内容均已被全局去重，属于可发布的确定终态。
                return new ActionCompletion(summary.status(), summary.items());
            }
        } catch (RuntimeException exception) {
            if (exception instanceof HttpClientErrorException clientException
                    && clientException.getStatusCode().value() == 425) {
                // 下游显式要求延后时保留 425 语义，由事件级处理释放租约且不消耗预算。
                throw exception;
            }
            if (terminalFailureAction(action)) {
                // 已绑定的本地失败或取消动作必须形成可解释终态，避免状态查询故障造成永久死信。
                LOGGER.warn("Completion item metadata unavailable for terminal local action: "
                                + "actionId={}, errorType={}",
                        action.id(), exception.getClass().getSimpleName());
                return fallbackCompletion(action);
            }
            if (!(exception instanceof HttpClientErrorException clientException)
                    || (clientException.getStatusCode().value() != 400
                    && clientException.getStatusCode().value() != 404)) {
                throw exception;
            }
            // 已明确失效的旧请求无法恢复，使用计划阶段保存的信息发送可解释的降级结果。
            LOGGER.warn("Completion item metadata permanently unavailable: actionId={}, errorType={}",
                    action.id(), exception.getClass().getSimpleName());
            return fallbackCompletion(action);
        }
        if ("SUCCEEDED".equals(action.status())) {
            throw new IllegalStateException("Succeeded action has no result summary");
        }
        // 未绑定请求或附件任务尚未解析出实际下载请求时，只能使用计划阶段元数据。
        return fallbackCompletion(action);
    }

    private <T> T externalCall(java.util.function.Supplier<T> call,
                               java.util.concurrent.Semaphore externalPermits, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new IllegalStateException("Completion relay event deadline exceeded");
        }
        try {
            if (!externalPermits.tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("Completion relay event deadline exceeded");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Completion relay was interrupted", exception);
        }
        try {
            T result = call.get();
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Completion relay event deadline exceeded");
            }
            return result;
        } finally {
            externalPermits.release();
        }
    }

    private void requireUsableSummary(AutomationRepository.ActionExecution action,
                                      DownloadIngestionClient.DownloadSummary summary) {
        if (!terminalFailureAction(action) && !action.status().equals(summary.status())) {
            // 本地成功动作仍必须与下游严格一致，避免掩盖真实的数据完整性问题。
            throw new IllegalStateException("Download result summary conflicts with local status");
        }
        for (DownloadIngestionClient.DownloadItem item : summary.items()) {
            if (item.fileName() == null || item.fileName().isBlank()
                    || !TERMINAL_TAG_STATUSES.contains(item.tagStatus())) {
                throw new IllegalStateException("Download tag summary is not terminal");
            }
            boolean tagged = "TAGGED".equals(item.tagStatus());
            if (tagged == item.tags().isEmpty()) {
                throw new IllegalStateException("Download tag summary is invalid");
            }
        }
    }

    private boolean terminalFailureAction(AutomationRepository.ActionExecution action) {
        return "FAILED".equals(action.status()) || "CANCELLED".equals(action.status());
    }

    private ActionCompletion fallbackCompletion(AutomationRepository.ActionExecution action) {
        return fallbackCompletion(action, action.status());
    }

    private ActionCompletion fallbackCompletion(AutomationRepository.ActionExecution action,
                                                String status) {
        return new ActionCompletion(status, java.util.List.of(metadataFallback(action, status)));
    }

    private DownloadIngestionClient.DownloadItem metadataFallback(
            AutomationRepository.ActionExecution action, String completionStatus) {
        String tagStatus = switch (completionStatus) {
            case "FAILED" -> "DOWNLOAD_FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "METADATA_UNAVAILABLE";
        };
        return new DownloadIngestionClient.DownloadItem(
                safeFallbackFileName(action), tagStatus, java.util.List.of());
    }

    private String safeFallbackFileName(AutomationRepository.ActionExecution action) {
        String fileName = action.fileName();
        if (fileName == null || fileName.isBlank() || fileName.contains("://")) {
            return "download-" + (action.sequence() + 1);
        }
        String leafName = fileName.substring(Math.max(
                fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        String safeName = leafName.replaceAll("[\\x00-\\x1f\\x7f]", "_").strip();
        if (safeName.isBlank()) {
            return "download-" + (action.sequence() + 1);
        }
        return safeName.substring(0, Math.min(180, safeName.length()));
    }

    private String resolvedCompletionStatus(String eventStatus, java.util.List<String> actionStatuses) {
        if (actionStatuses.isEmpty()) {
            return eventStatus;
        }
        boolean succeeded = actionStatuses.contains("SUCCEEDED");
        boolean failed = actionStatuses.contains("FAILED");
        boolean cancelled = actionStatuses.contains("CANCELLED");
        if (succeeded && (failed || cancelled)) {
            return "PARTIAL_FAILED";
        }
        if (failed) {
            return "FAILED";
        }
        if (cancelled) {
            return "CANCELLED";
        }
        return succeeded ? "SUCCEEDED" : eventStatus;
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
        if ("DOWNLOAD_FAILED".equals(item.tagStatus())) {
            return "失败（下载未完成）";
        }
        if ("CANCELLED".equals(item.tagStatus())) {
            return "已取消";
        }
        if ("METADATA_UNAVAILABLE".equals(item.tagStatus())) {
            return "标签信息不可用（文件已保存）";
        }
        return "处理中";
    }

    private record ActionCompletion(
            String status, java.util.List<DownloadIngestionClient.DownloadItem> items) {
    }
}
