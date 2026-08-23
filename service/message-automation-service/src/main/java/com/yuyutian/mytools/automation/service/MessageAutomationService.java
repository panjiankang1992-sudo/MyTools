package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.model.AutomationRuleRecord;
import com.yuyutian.mytools.automation.model.AutomationRunView;
import com.yuyutian.mytools.automation.model.CreateAutomationRuleRequest;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.model.ErrorCode;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
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
            // 消息标识是严格去重键，失败运行由对账流程修复，不重新匹配可能已变化的规则。
            return existing;
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
        List<String> urls = extractUrls(message.body().substring(rule.commandPrefix().length()), rule.maxActions());
        if (urls.isEmpty()) {
            return transactionTemplate.execute(status -> repository.completeRun(
                    messageId, "FAILED", List.of(), ErrorCode.NO_ACTION_INPUT.code()));
        }
        List<String> refs = new ArrayList<>();
        try {
            for (int index = 0; index < urls.size(); index++) {
                String url = urls.get(index);
                refs.add(downloadClient.create(messageId, rule.id(), index, rule.requestKind(), url,
                        fileName(url, index)));
            }
            return transactionTemplate.execute(status -> repository.completeRun(
                    messageId, "SUCCEEDED", refs, null));
        } catch (RuntimeException exception) {
            String status = refs.isEmpty() ? "FAILED" : "PARTIAL_FAILED";
            return transactionTemplate.execute(transactionStatus -> repository.completeRun(
                    messageId, status, refs, ErrorCode.DOWNLOAD_CREATE_FAILED.code()));
        }
    }

    private boolean matches(AutomationRuleRecord rule, InboundMessage message) {
        return (rule.conversationKey() == null || rule.conversationKey().equals(message.conversationKey()))
                && (rule.sender() == null || rule.sender().equalsIgnoreCase(message.sender()))
                && message.body().startsWith(rule.commandPrefix());
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
