package com.yuyutian.mytools.automation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.automation.model.AutomationRuleRecord;
import com.yuyutian.mytools.automation.model.AutomationRunView;
import com.yuyutian.mytools.automation.model.CreateAutomationRuleRequest;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.model.ErrorCode;
import com.yuyutian.mytools.automation.model.AutomationActionView;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 授权消息规则匹配与下载动作编排服务。
 */
@Service
public class MessageAutomationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageAutomationService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVALID_FILE_NAME = Pattern.compile("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]");
    private final AutomationRepository repository;
    private final MessagingClient messagingClient;
    private final DownloadIngestionClient downloadClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建消息自动化服务。
     */
    public MessageAutomationService(AutomationRepository repository, MessagingClient messagingClient,
                                    DownloadIngestionClient downloadClient,
                                    TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.messagingClient = messagingClient;
        this.downloadClient = downloadClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 创建具有固定下载动作的授权规则。
     */
    public AutomationRuleRecord createRule(CreateAutomationRuleRequest request) {
        return transactionTemplate.execute(status -> repository.createRule(request));
    }

    /**
     * 幂等处理一个标准入站消息。
     */
    public AutomationRunView process(UUID messageId) {
        AutomationRunView existing = repository.findRun(messageId).orElse(null);
        if (existing != null) {
            return reconcile(existing, true);
        }
        InboundMessage message = messagingClient.get(messageId);
        AutomationRuleRecord rule = repository.findEnabledRules(message.ownerId(), message.channelType()).stream()
                .filter(candidate -> matches(candidate, message)).findFirst().orElse(null);
        AutomationRunView started = transactionTemplate.execute(status -> repository.beginRun(messageId, rule));
        if (started == null) {
            throw new IllegalStateException("Automation transaction returned no run");
        }
        if (rule == null) {
            return transactionTemplate.execute(status -> repository.completeRun(messageId, "NO_MATCH", List.of(), null));
        }
        List<InboundMessage.MessagePart> attachments = message.parts().stream()
                .filter(part -> "ATTACHMENT".equals(part.type())).limit(rule.maxActions()).toList();
        // 渠道已提供结构化附件时优先走附件任务，保留原文件名并避免重复下载正文中的同一URL。
        if ("MESSAGE_ATTACHMENT".equals(rule.requestKind()) || !attachments.isEmpty()) {
            if (attachments.isEmpty()) return noInput(messageId);
            for (int index = 0; index < attachments.size(); index++) {
                int sequence = index;
                InboundMessage.MessagePart part = attachments.get(index);
                var action = transactionTemplate.execute(status -> repository.createAction(started.id(), sequence,
                        "ATTACHMENT_DOWNLOAD", part.id().toString(), attachmentName(part, sequence)));
                if (action == null) throw new IllegalStateException("Automation action transaction returned no action");
                submitAttachment(message, action.id(), part.id());
            }
        } else {
            List<String> urls = extractUrls(message.body(), rule.maxActions());
            if (urls.isEmpty()) return noInput(messageId);
            if (urls.size() > 1) {
                var action = transactionTemplate.execute(status -> repository.createAction(
                        started.id(), 0, "DOWNLOAD_BATCH", batchInput(urls), "message-url-batch"));
                if (action == null) throw new IllegalStateException("Automation action transaction returned no action");
                submitBatch(started, message, rule.id(), action.id(), urls);
                return reconcile(repository.findRun(messageId).orElseThrow(), false);
            }
            for (int index = 0; index < urls.size(); index++) {
                int sequence = index;
                String url = urls.get(index);
                var action = transactionTemplate.execute(status -> repository.createAction(
                        started.id(), sequence, "DOWNLOAD_REQUEST", url, fileName(url, sequence)));
                if (action == null) throw new IllegalStateException("Automation action transaction returned no action");
                submit(started, message, rule.id(), rule.requestKind(), action.id(), sequence, url,
                        fileName(url, sequence));
            }
        }
        return reconcile(repository.findRun(messageId).orElseThrow(), false);
    }

    /**
     * 查询并对账消息自动化运行。
     */
    public AutomationRunView get(UUID messageId) {
        AutomationRunView run = repository.findRun(messageId).orElseThrow(AutomationRunNotFoundException::new);
        return reconcile(run, true);
    }

    /**
     * 级联取消运行中下载子动作。
     */
    public AutomationRunView cancel(UUID runId) {
        AutomationRunView run = repository.findRunById(runId).orElseThrow(AutomationRunNotFoundException::new);
        InboundMessage message = messagingClient.get(run.messageId());
        for (AutomationActionView action : repository.findActions(run.id())) {
            if (action.externalRequestId() != null && !terminalAction(action.status())) {
                try {
                    String status = "ATTACHMENT_DOWNLOAD".equals(action.actionType())
                            ? messagingClient.cancelAttachment(action.externalRequestId(), message.ownerId()).status()
                            : downloadClient.cancel(action.externalRequestId(), message.ownerId()).status();
                    repository.updateActionStatus(action.id(), mapActionStatus(status), null);
                } catch (RuntimeException exception) {
                    repository.updateActionStatus(action.id(), action.status(), "CANCEL_REQUEST_FAILED");
                }
            }
        }
        return aggregate(run.messageId());
    }

    private void submit(AutomationRunView run, InboundMessage message, UUID ruleId, String requestKind,
                        UUID actionId, int index, String url, String fileName) {
        try {
            UUID requestId = UUID.fromString(downloadClient.create(run.messageId(), message.ownerId(), ruleId,
                    index, requestKind, url, fileName, message.receivedAt()));
            transactionTemplate.executeWithoutResult(status -> repository.bindAction(actionId, requestId));
        } catch (RuntimeException exception) {
            logDownloadFailure(actionId, exception);
            transactionTemplate.executeWithoutResult(status ->
                    repository.failAction(actionId, ErrorCode.DOWNLOAD_CREATE_FAILED.code()));
        }
    }

    private void submitBatch(AutomationRunView run, InboundMessage message, UUID ruleId,
                             UUID actionId, List<String> urls) {
        try {
            UUID requestId = UUID.fromString(downloadClient.createBatch(run.messageId(), message.ownerId(),
                    ruleId, urls, message.receivedAt()));
            transactionTemplate.executeWithoutResult(status -> repository.bindAction(actionId, requestId));
        } catch (RuntimeException exception) {
            logDownloadFailure(actionId, exception);
            transactionTemplate.executeWithoutResult(status ->
                    repository.failAction(actionId, ErrorCode.DOWNLOAD_CREATE_FAILED.code()));
        }
    }

    private void logDownloadFailure(UUID actionId, RuntimeException exception) {
        String reason = exception.getClass().getSimpleName();
        if (exception instanceof RestClientResponseException response) {
            String safeBody = response.getResponseBodyAsString().replaceAll("[^A-Za-z0-9 _.-]", "_");
            reason = "HTTP_" + response.getStatusCode().value() + "_"
                    + safeBody.substring(0, Math.min(160, safeBody.length()));
        }
        LOGGER.warn("Download action submission failed: actionId={}, reason={}", actionId, reason);
    }

    private void submitAttachment(InboundMessage message, UUID actionId, UUID partId) {
        try {
            UUID jobId = messagingClient.createAttachment(message.id(), partId, message.ownerId()).id();
            transactionTemplate.executeWithoutResult(status -> repository.bindAction(actionId, jobId));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status ->
                    repository.failAction(actionId, ErrorCode.DOWNLOAD_CREATE_FAILED.code()));
        }
    }

    private AutomationRunView reconcile(AutomationRunView run, boolean recoverCreating) {
        InboundMessage message = messagingClient.get(run.messageId());
        for (AutomationRepository.ActionExecution action : repository.findActionExecutions(run.id())) {
            if ("CREATING".equals(action.status()) && recoverCreating && run.ruleId() != null) {
                if ("ATTACHMENT_DOWNLOAD".equals(action.actionType())) {
                    submitAttachment(message, action.id(), UUID.fromString(action.sourceUrl()));
                } else if ("DOWNLOAD_BATCH".equals(action.actionType())) {
                    submitBatch(run, message, run.ruleId(), action.id(), batchUrls(action.sourceUrl()));
                } else {
                    String requestKind = repository.findRule(run.ruleId()).orElseThrow().requestKind();
                    submit(run, message, run.ruleId(), requestKind, action.id(), action.sequence(),
                            action.sourceUrl(), action.fileName());
                }
            } else if (action.externalRequestId() != null && !terminalAction(action.status())) {
                try {
                    String status = "ATTACHMENT_DOWNLOAD".equals(action.actionType())
                            ? messagingClient.attachment(action.externalRequestId(), message.ownerId()).status()
                            : downloadClient.get(action.externalRequestId(), message.ownerId()).status();
                    repository.updateActionStatus(action.id(), mapActionStatus(status), null);
                } catch (RuntimeException exception) {
                    // 临时查询失败不覆盖已知子任务状态，下一次查询继续对账。
                }
            }
        }
        return aggregate(run.messageId());
    }

    private AutomationRunView aggregate(UUID messageId) {
        AutomationRunView run = repository.findRun(messageId).orElseThrow();
        List<AutomationActionView> actions = repository.findActions(run.id());
        if (actions.isEmpty()) {
            return run;
        }
        long active = actions.stream().filter(action -> !terminalAction(action.status())).count();
        long succeeded = actions.stream().filter(action -> "SUCCEEDED".equals(action.status())).count();
        long failed = actions.stream().filter(action -> "FAILED".equals(action.status())).count();
        long cancelled = actions.stream().filter(action -> "CANCELLED".equals(action.status())).count();
        String status;
        String error = null;
        if (active > 0) {
            status = "RUNNING";
        } else if (failed > 0) {
            status = succeeded > 0 ? "PARTIAL_FAILED" : "FAILED";
            error = ErrorCode.DOWNLOAD_CREATE_FAILED.code();
        } else if (cancelled > 0) {
            status = "CANCELLED";
        } else {
            status = "SUCCEEDED";
        }
        String finalError = error;
        return transactionTemplate.execute(transactionStatus ->
                repository.updateRunAggregate(messageId, status, finalError));
    }

    private String mapActionStatus(String status) {
        return switch (status) {
            case "ACCEPTED", "PLANNING", "QUEUED", "RESOLVING", "RUNNING", "CANCELLING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED", "TIMED_OUT" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> throw new IllegalStateException("Unsupported action status");
        };
    }

    private AutomationRunView noInput(UUID messageId) {
        return transactionTemplate.execute(status -> repository.completeRun(
                messageId, "FAILED", List.of(), ErrorCode.NO_ACTION_INPUT.code()));
    }

    private String attachmentName(InboundMessage.MessagePart part, int index) {
        String value = part.fileName() == null || part.fileName().isBlank()
                ? "attachment-" + index + ".bin" : part.fileName();
        String safe = INVALID_FILE_NAME.matcher(value).replaceAll("_");
        return safe.isBlank() ? "attachment-" + index + ".bin"
                : safe.substring(0, Math.min(180, safe.length()));
    }

    private boolean terminalAction(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean matches(AutomationRuleRecord rule, InboundMessage message) {
        return (rule.conversationKey() == null || rule.conversationKey().equals(message.conversationKey()))
                && (rule.sender() == null || rule.sender().equalsIgnoreCase(message.sender()))
                && message.body() != null && message.body().startsWith(rule.commandPrefix());
    }

    private List<String> extractUrls(String value, int limit) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(value);
        while (matcher.find() && urls.size() < limit) {
            String candidate = matcher.group().replaceAll("[),.;]+$", "");
            try {
                URI uri = new URI(candidate);
                // 只允许明确的公网协议形态，实际 SSRF 防护由下载任务再次执行。
                if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && !uri.getHost().isBlank()) {
                    urls.add(candidate);
                }
            } catch (URISyntaxException ignored) {
                // 无效 URL 不是动作，继续解析其他候选值。
            }
        }
        return List.copyOf(urls);
    }

    private String batchInput(List<String> urls) {
        try {
            String value = OBJECT_MAPPER.writeValueAsString(urls);
            if (value.length() > 4096) throw new IllegalArgumentException("Message URL batch is too large");
            return value;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Message URL batch is invalid", exception);
        }
    }

    private List<String> batchUrls(String value) {
        try {
            List<String> urls = OBJECT_MAPPER.readValue(value, new TypeReference<List<String>>() { });
            if (urls.size() < 2 || urls.size() > 20 || urls.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Message URL batch is invalid");
            }
            return List.copyOf(urls);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Message URL batch is invalid", exception);
        }
    }

    private String fileName(String url, int index) {
        try {
            String path = new URI(url).getPath();
            String raw = path == null || path.isBlank() || path.endsWith("/")
                    ? "download-" + index + ".bin" : path.substring(path.lastIndexOf('/') + 1);
            String safe = INVALID_FILE_NAME.matcher(raw).replaceAll("_");
            return safe.isBlank() ? "download-" + index + ".bin" : safe.substring(0, Math.min(180, safe.length()));
        } catch (URISyntaxException exception) {
            return "download-" + index + ".bin";
        }
    }
}
