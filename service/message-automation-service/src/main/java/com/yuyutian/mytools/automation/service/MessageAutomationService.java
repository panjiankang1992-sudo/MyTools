package com.yuyutian.mytools.automation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.AutomationActionView;
import com.yuyutian.mytools.automation.model.AutomationRuleRecord;
import com.yuyutian.mytools.automation.model.AutomationRunView;
import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.ClaimMessageLinksRequest;
import com.yuyutian.mytools.automation.model.CreateAutomationRuleRequest;
import com.yuyutian.mytools.automation.model.ErrorCode;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final int MAXIMUM_SUBMISSION_ATTEMPTS = 10;
    private static final int MAXIMUM_STATUS_POLL_FAILURES = 8;
    private static final int MAXIMUM_URLS_PER_BATCH = 20;
    private static final int MAXIMUM_BATCH_PAYLOAD_LENGTH = 4096;
    private static final int MAXIMUM_RECONCILIATION_ACTIONS_PER_RUN = 4;
    private static final int MAXIMUM_URL_CANDIDATES_PER_MESSAGE = 10_000;
    // 附件描述只保留文件名（如 32 位十六进制.jpg），裸域名分支会把这类文件名误判为链接。
    // 该集合只用于无协议的裸候选；带明确 http/https 协议的链接不受影响。
    private static final Set<String> ATTACHMENT_FILE_EXTENSIONS = Set.of(
            "avif", "bmp", "gif", "heic", "heif", "ico", "jpeg", "jpg", "png", "svg", "tif", "tiff", "webp",
            "avi", "flv", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv",
            "aac", "amr", "flac", "m4a", "mp3", "ogg", "opus", "wav", "wma",
            "csv", "doc", "docx", "json", "pdf", "ppt", "pptx", "txt", "xls", "xlsx", "xml", "yaml", "yml",
            "7z", "bz2", "gz", "rar", "tar", "tgz", "xz", "zip");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:magnet:\\?|https?://|(?<![@\\w])(?:[a-z0-9-]+\\.)+[a-z]{2,}(?=/|\\b))[^\\s<>\"'\\u3000-\\u303f\\uff00-\\uffef]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INVALID_FILE_NAME = Pattern.compile("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]");
    private final AutomationRepository repository;
    private final MessagingClient messagingClient;
    private final DownloadIngestionClient downloadClient;
    private final TransactionTemplate transactionTemplate;
    private final int maxActionsPerMessage;
    private final Duration actionStatusPollDelay;

    /**
     * 创建消息自动化服务。
     */
    public MessageAutomationService(AutomationRepository repository, MessagingClient messagingClient,
                                    DownloadIngestionClient downloadClient,
                                    TransactionTemplate transactionTemplate, AutomationProperties properties) {
        this.repository = repository;
        this.messagingClient = messagingClient;
        this.downloadClient = downloadClient;
        this.transactionTemplate = transactionTemplate;
        this.maxActionsPerMessage = properties.maxActionsPerMessage();
        this.actionStatusPollDelay = Duration.ofMillis(properties.actionStatusPollDelayMs());
    }

    /**
     * 创建具有固定下载动作的授权规则。
     */
    public AutomationRuleRecord createRule(CreateAutomationRuleRequest request) {
        return transactionTemplate.execute(status -> repository.createRule(request));
    }

    /**
     * 为一个已存在的消息运行批量登记派生链接。
     */
    public List<String> claimLinks(ClaimMessageLinksRequest request) {
        if (repository.findRun(request.messageId()).isEmpty()) {
            throw new IllegalArgumentException("Message automation run does not exist");
        }
        List<String> claimed = new ArrayList<>();
        for (String value : request.urls()) {
            String normalized = normalizeUrl(value);
            AutomationRepository.LinkClaim result = transactionTemplate.execute(status -> repository.claimLink(
                    request.ownerId(), request.messageId(), normalized, sha256(normalized), request.processedAt()));
            if (result != null && result.claimed()) {
                claimed.add(normalized);
            }
        }
        return List.copyOf(claimed);
    }

    /**
     * 幂等处理一个标准入站消息。
     */
    public AutomationRunView process(UUID messageId) {
        AutomationRunView existing = repository.findRun(messageId).orElse(null);
        if (existing != null) {
            return replayExistingRun(messageId, existing);
        }
        InboundMessage message = messagingClient.get(messageId);
        AutomationRuleRecord rule = repository.findEnabledRules(message.ownerId(), message.channelType()).stream()
                .filter(candidate -> matches(candidate, message)).findFirst().orElse(null);
        MessagePlan plan = transactionTemplate.execute(status -> createPlan(message, rule));
        if (plan == null) {
            throw new IllegalStateException("Automation transaction returned no plan");
        }
        if (plan.existing()) {
            // 初次快照之后才提交的并发运行属于另一事务，当前请求不得再次规划或终态化。
            return replayExistingRun(messageId, plan.run());
        }
        if (!plan.dispatch()) {
            // 纯重复链接已先写入可靠事件；同步快路径失败时由 Outbox 接管。
            replyDuplicateLinks(message, plan.run().id(), plan.duplicates(), plan.duplicateEventId());
            return plan.run();
        }
        // 动作计划已全部入库，请求线程只发送受理回执；后台对账器负责提交并恢复下载。
        if (!message.preAcknowledged()) {
            // OneBot 提前受理事件已经可靠回复时，Automation 不再发送第二条重复 ACK。
            acknowledge(messageId, plan.run().id());
        }
        // 混合消息先完成受理，再补充至多一次重复链接提示。
        replyDuplicateLinks(message, plan.run().id(), plan.duplicates(), null);
        return plan.run();
    }

    private AutomationRunView replayExistingRun(UUID messageId, AutomationRunView existing) {
        if (existing.ruleId() == null || !"SUCCEEDED".equals(existing.status())
                || existing.actionCount() != 0 || existing.errorCode() != null
                || !existing.actions().isEmpty()) {
            // 常规重复投递保持纯读取快路径，不发起下载或外部状态查询。
            return existing;
        }
        // 旧版纯重复运行可能没有反馈事件；仅在实际重投时原子补建，避免批量打扰历史会话。
        AutomationRepository.DuplicateCompletion repaired = transactionTemplate.execute(
                status -> repository.ensureLegacyDuplicateOnlyFeedback(messageId));
        if (repaired == null) {
            throw new IllegalStateException("Automation repair transaction returned no result");
        }
        repaired.eventId().ifPresent(eventId ->
                replyDuplicateFeedback(messageId, repaired.run().id(), eventId));
        // 已有专用事件时仍保持只读结果，不重复创建或发送同步反馈。
        return repaired.run();
    }

    private MessagePlan createPlan(InboundMessage message, AutomationRuleRecord rule) {
        AutomationRepository.RunStart start = repository.beginRun(message.id(), rule);
        if (!start.created()) {
            return MessagePlan.existing(start.run());
        }
        AutomationRunView started = start.run();
        if (rule == null) {
            // 普通无关消息保持静默；已可靠提前受理的转发必须补发终态，不能只留下承诺性 ACK。
            AutomationRunView completed = repository.completeRun(
                    message.id(), "NO_MATCH", List.of(), null, message.preAcknowledged());
            return MessagePlan.terminal(completed, List.of());
        }
        // 规则上限表达该授权每条消息允许处理的资源数，且不得突破服务全局安全上限。
        int actionBudget = Math.min(maxActionsPerMessage, rule.maxActions());
        List<InboundMessage.MessagePart> attachments = message.parts().stream()
                .filter(part -> "ATTACHMENT".equals(part.type())).limit(actionBudget).toList();
        int sequence = 0;
        for (InboundMessage.MessagePart part : attachments) {
            int currentSequence = sequence++;
            repository.createAction(started.id(), currentSequence,
                    "ATTACHMENT_DOWNLOAD", part.id().toString(), attachmentName(part, currentSequence));
        }
        int remainingBudget = Math.max(0, actionBudget - attachments.size());
        // 多扫描有界候选，以便前部重复链接不会错误占用真正动作的授权预算。
        List<String> extractedUrls = extractUrls(message.body(), MAXIMUM_URL_CANDIDATES_PER_MESSAGE);
        List<String> urls = new ArrayList<>();
        List<AutomationRepository.LinkClaim> duplicates = new ArrayList<>();
        for (String candidate : extractedUrls) {
            if (urls.size() >= remainingBudget) {
                break;
            }
            String normalized = normalizeUrl(candidate);
            AutomationRepository.LinkClaim claim = repository.claimLink(
                    message.ownerId(), message.id(), normalized, sha256(normalized), message.receivedAt());
            if (claim.claimed()) {
                urls.add(normalized);
            } else {
                duplicates.add(claim);
            }
        }
        int activeActions = attachments.size();
        for (int offset = 0; offset < urls.size();) {
            int currentSequence = sequence++;
            if (MagnetLink.isMagnet(urls.get(offset))) {
                // 磁力链接独立编排，禁止进入只支持 HTTP 的链接批次。
                String magnet = urls.get(offset++);
                var action = repository.createAction(started.id(), currentSequence,
                        "DOWNLOAD_REQUEST", magnet.length() <= MAXIMUM_BATCH_PAYLOAD_LENGTH
                                ? magnet : "oversized-magnet:" + sha256(magnet),
                        "magnet-" + currentSequence);
                if (magnet.length() > MAXIMUM_BATCH_PAYLOAD_LENGTH) {
                    repository.updateActionStatus(action.id(), "CREATING", "FAILED",
                            ErrorCode.ACTION_INPUT_TOO_LARGE.code(), actionStatusPollDelay);
                }
                activeActions++;
                continue;
            }
            String firstPayload = serializeBatch(List.of(urls.get(offset)));
            if (firstPayload.length() > MAXIMUM_BATCH_PAYLOAD_LENGTH) {
                String url = urls.get(offset++);
                var action = repository.createAction(started.id(), currentSequence,
                        "DOWNLOAD_REQUEST", "oversized-url:" + sha256(url), fileName(url, currentSequence));
                repository.updateActionStatus(action.id(), "CREATING", "FAILED",
                        ErrorCode.ACTION_INPUT_TOO_LARGE.code(), actionStatusPollDelay);
                continue;
            }
            List<String> chunk = new ArrayList<>();
            String payload = firstPayload;
            while (offset < urls.size() && chunk.size() < MAXIMUM_URLS_PER_BATCH) {
                if (MagnetLink.isMagnet(urls.get(offset))) {
                    break;
                }
                List<String> candidate = new ArrayList<>(chunk);
                candidate.add(urls.get(offset));
                String candidatePayload = serializeBatch(candidate);
                if (candidatePayload.length() > MAXIMUM_BATCH_PAYLOAD_LENGTH) {
                    break;
                }
                chunk.add(urls.get(offset++));
                payload = candidatePayload;
            }
            if (chunk.size() > 1) {
                repository.createAction(started.id(), currentSequence,
                        "DOWNLOAD_BATCH", payload, "message-url-batch-" + currentSequence);
            } else {
                String url = chunk.get(0);
                String plannedFileName = fileName(url, currentSequence);
                repository.createAction(started.id(), currentSequence,
                        "DOWNLOAD_REQUEST", url, plannedFileName);
            }
            activeActions++;
        }
        if (attachments.isEmpty() && urls.isEmpty()) {
            String status = duplicates.isEmpty() ? "FAILED" : "SUCCEEDED";
            String error = duplicates.isEmpty() ? ErrorCode.NO_ACTION_INPUT.code() : null;
            if (!duplicates.isEmpty()) {
                // 专用反馈事件与运行终态同事务提交，避免同步回复失败后永久无反馈。
                AutomationRepository.DuplicateCompletion completion =
                        repository.completeDuplicateOnlyRun(message.id());
                return MessagePlan.terminal(completion.run(), List.copyOf(duplicates),
                        completion.eventId().orElse(null));
            }
            AutomationRunView completed = repository.completeRun(
                    message.id(), status, List.of(), error);
            return MessagePlan.terminal(completed, List.of());
        }
        String plannedStatus = activeActions == 0 ? "FAILED" : "RUNNING";
        String plannedError = activeActions == 0 ? ErrorCode.ACTION_INPUT_TOO_LARGE.code() : null;
        AutomationRunView planned = repository.updateRunAggregate(message.id(), plannedStatus, plannedError);
        if (activeActions == 0) {
            repository.completeLinks(message.id(), plannedStatus);
            return MessagePlan.terminal(planned, List.copyOf(duplicates));
        }
        return new MessagePlan(planned, List.copyOf(duplicates), true, null, false);
    }

    private record MessagePlan(AutomationRunView run, List<AutomationRepository.LinkClaim> duplicates,
                               boolean dispatch, UUID duplicateEventId, boolean existing) {

        private static MessagePlan existing(AutomationRunView run) {
            return new MessagePlan(run, List.of(), false, null, true);
        }

        private static MessagePlan terminal(AutomationRunView run,
                                            List<AutomationRepository.LinkClaim> duplicates) {
            return terminal(run, duplicates, null);
        }

        private static MessagePlan terminal(AutomationRunView run,
                                            List<AutomationRepository.LinkClaim> duplicates,
                                            UUID duplicateEventId) {
            return new MessagePlan(run, duplicates, false, duplicateEventId, false);
        }
    }

    private void acknowledge(UUID messageId, UUID runId) {
        try {
            messagingClient.reply(messageId, "automation-start-" + runId,
                    "已收到，正在处理；完成后会发送文件名和标签信息。");
        } catch (RuntimeException exception) {
            // 回执失败不能阻断消息入库和任务创建，终态通知仍由可靠 outbox 重试。
            LOGGER.warn("Automation acknowledgement failed: runId={}, errorType={}",
                    runId, exception.getClass().getSimpleName());
        }
    }

    /**
     * 只读查询消息自动化运行，不触发任何外部调用或状态推进。
     */
    public AutomationRunView get(UUID messageId) {
        return repository.findRun(messageId).orElseThrow(AutomationRunNotFoundException::new);
    }

    /**
     * 使用持久化运行租约推进一轮到期动作。
     *
     * @param claim 运行租约
     * @return 当前权威运行快照
     */
    public AutomationRunView reconcileClaimed(AutomationRepository.RunClaim claim) {
        if (!repository.ownsRunClaim(claim)) {
            return get(claim.messageId());
        }
        AutomationRunView run = repository.findRunById(claim.runId())
                .orElseThrow(AutomationRunNotFoundException::new);
        if (!"RUNNING".equals(run.status())) {
            return run;
        }
        List<AutomationActionView> persistedActions = repository.findActions(run.id());
        if (persistedActions.isEmpty()) {
            // 兼容修复前可能遗留的零动作运行，避免它永久停留在 RUNNING。
            return noInput(claim);
        }
        if (persistedActions.stream().allMatch(action -> terminalAction(action.status()))) {
            // worker 在动作终态落库后崩溃时，下一租约仍可补齐运行聚合和完成事件。
            return aggregate(claim);
        }

        List<AutomationRepository.ActionExecution> creatingCandidates =
                repository.findDueCreatingActions(run.id(), MAXIMUM_RECONCILIATION_ACTIONS_PER_RUN);
        // 有待提交动作时至少保留一个名额；其余名额优先批量发现下载终态。
        int submittedLimit = creatingCandidates.isEmpty()
                ? MAXIMUM_RECONCILIATION_ACTIONS_PER_RUN
                : MAXIMUM_RECONCILIATION_ACTIONS_PER_RUN - 1;
        List<AutomationRepository.ActionExecution> submitted = repository.findDueSubmittedActions(
                run.id(), submittedLimit);
        int creatingLimit = MAXIMUM_RECONCILIATION_ACTIONS_PER_RUN - submitted.size();
        List<AutomationRepository.ActionExecution> creating = List.copyOf(
                creatingCandidates.subList(0, Math.min(creatingLimit, creatingCandidates.size())));
        if (creating.isEmpty() && submitted.isEmpty()) {
            return run;
        }

        // 消息正文只在确有到期外部动作时读取，纯 GET 和退避窗口都不会调用下游。
        InboundMessage message = messagingClient.get(run.messageId());
        for (AutomationRepository.ActionExecution action : submitted) {
            if (!repository.ownsRunClaim(claim)) {
                return get(claim.messageId());
            }
            // 同一 run 内顺序轮询，优先发现终态且不放大跨 run 的外部并发上限。
            pollSubmittedAction(claim, run, message, action);
        }
        for (AutomationRepository.ActionExecution action : creating) {
            if (!repository.ownsRunClaim(claim)) {
                return get(claim.messageId());
            }
            if (run.ruleId() == null) {
                repository.updateActionStatus(claim, action.id(), action.status(), "FAILED",
                        ErrorCode.ACTION_STATUS_QUERY_FAILED.code(), actionStatusPollDelay);
                continue;
            }
            try {
                if ("ATTACHMENT_DOWNLOAD".equals(action.actionType())) {
                    submitAttachment(claim, message, action.id(), UUID.fromString(action.sourceUrl()));
                } else if ("DOWNLOAD_BATCH".equals(action.actionType())) {
                    submitBatch(claim, run, message, run.ruleId(), action.id(), action.sequence(),
                            batchUrls(action.sourceUrl()));
                } else {
                    submit(claim, run, message, run.ruleId(), "HTTP_ASSET", action.id(), action.sequence(),
                            action.sourceUrl(), action.fileName());
                }
            } catch (IllegalArgumentException exception) {
                // 已持久化动作输入损坏属于不可恢复协议错误，直接终止以免毒化公平轮转。
                repository.updateActionStatus(claim, action.id(), action.status(), "FAILED",
                        ErrorCode.ACTION_STATUS_QUERY_FAILED.code(), actionStatusPollDelay);
            }
        }
        return aggregate(claim);
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
                    repository.updateActionStatus(action.id(), action.status(), mapActionStatus(status), null,
                            actionStatusPollDelay);
                } catch (RuntimeException exception) {
                    repository.updateActionStatus(action.id(), action.status(), action.status(),
                            "CANCEL_REQUEST_FAILED", actionStatusPollDelay);
                }
            }
        }
        return aggregate(run.messageId());
    }

    private void submit(AutomationRepository.RunClaim claim, AutomationRunView run, InboundMessage message,
                        UUID ruleId, String requestKind, UUID actionId, int index, String url, String fileName) {
        try {
            UUID requestId = UUID.fromString(downloadClient.create(run.messageId(), message.ownerId(), ruleId,
                    index, requestKind, url, fileName, message.receivedAt()));
            transactionTemplate.executeWithoutResult(status -> repository.bindAction(
                    claim, actionId, requestId, actionStatusPollDelay));
        } catch (RuntimeException exception) {
            logDownloadFailure(actionId, exception);
            transactionTemplate.executeWithoutResult(status ->
                    repository.failAction(claim, actionId, ErrorCode.DOWNLOAD_CREATE_FAILED.code(),
                            MAXIMUM_SUBMISSION_ATTEMPTS));
        }
    }

    private void submitBatch(AutomationRepository.RunClaim claim, AutomationRunView run, InboundMessage message,
                             UUID ruleId, UUID actionId, int sequence, List<String> urls) {
        try {
            UUID requestId = UUID.fromString(downloadClient.createBatch(run.messageId(), message.ownerId(),
                    ruleId, sequence, urls, message.receivedAt(), message.body()));
            transactionTemplate.executeWithoutResult(status -> repository.bindAction(
                    claim, actionId, requestId, actionStatusPollDelay));
        } catch (RuntimeException exception) {
            logDownloadFailure(actionId, exception);
            transactionTemplate.executeWithoutResult(status ->
                    repository.failAction(claim, actionId, ErrorCode.DOWNLOAD_CREATE_FAILED.code(),
                            MAXIMUM_SUBMISSION_ATTEMPTS));
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

    private void submitAttachment(AutomationRepository.RunClaim claim, InboundMessage message,
                                  UUID actionId, UUID partId) {
        try {
            UUID jobId = messagingClient.createAttachment(message.id(), partId, message.ownerId()).id();
            transactionTemplate.executeWithoutResult(status -> repository.bindAction(
                    claim, actionId, jobId, actionStatusPollDelay));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status ->
                    repository.failAction(claim, actionId, ErrorCode.DOWNLOAD_CREATE_FAILED.code(),
                            MAXIMUM_SUBMISSION_ATTEMPTS));
        }
    }

    private void pollSubmittedAction(AutomationRepository.RunClaim claim, AutomationRunView run,
                                     InboundMessage message, AutomationRepository.ActionExecution action) {
        String mappedStatus;
        try {
            String externalStatus = "ATTACHMENT_DOWNLOAD".equals(action.actionType())
                    ? messagingClient.attachment(action.externalRequestId(), message.ownerId()).status()
                    : downloadClient.get(action.externalRequestId(), message.ownerId()).status();
            mappedStatus = mapActionStatus(externalStatus);
            if (!repository.updateActionStatus(claim, action.id(), action.status(), mappedStatus, null,
                    actionStatusPollDelay)) {
                return;
            }
        } catch (RuntimeException exception) {
            boolean permanent = permanentStatusPollFailure(exception);
            // 查询失败使用独立持久预算；永久错误立即终止，瞬时错误退避并参与公平轮转。
            repository.failActionPoll(claim, action.id(), ErrorCode.ACTION_STATUS_QUERY_FAILED.code(),
                    MAXIMUM_STATUS_POLL_FAILURES, permanent);
            LOGGER.warn("Automation action status query failed: actionId={}, permanent={}, errorType={}",
                    action.id(), permanent, exception.getClass().getSimpleName());
            return;
        }
        if (!"ATTACHMENT_DOWNLOAD".equals(action.actionType()) && !terminalAction(mappedStatus)) {
            try {
                relayProgress(claim, run, message, action);
            } catch (RuntimeException exception) {
                // 进度反馈仅是尽力通知，失败不得消耗状态查询预算或提前终止仍在运行的下载。
                LOGGER.warn("Automation progress relay failed: actionId={}, errorType={}",
                        action.id(), exception.getClass().getSimpleName());
            }
        }
    }

    private boolean permanentStatusPollFailure(RuntimeException exception) {
        if (exception instanceof RestClientResponseException response) {
            int statusCode = response.getStatusCode().value();
            // 请求超时、过早请求和限流仍可能恢复，其余客户端错误无需继续占用轮询预算。
            return statusCode >= 400 && statusCode < 500
                    && statusCode != 408 && statusCode != 425 && statusCode != 429;
        }
        // 未知状态、损坏响应和缺失必需字段均属于协议错误，重试不会改变结果。
        return exception instanceof IllegalStateException;
    }

    private void relayProgress(AutomationRepository.RunClaim claim, AutomationRunView run, InboundMessage message,
                               AutomationRepository.ActionExecution action) {
        DownloadIngestionClient.DownloadSummary summary = downloadClient.summary(action.externalRequestId());
        int percent = summary.progressPercent();
        boolean qqChannel = message.channelType() == ChannelType.QQ;
        if (summary.totalBytes() <= 10L * 1024 * 1024 || percent <= action.lastProgressPercent()
                || (!qqChannel && percent % 5 != 0)) {
            return;
        }
        int interval = qqChannel ? 25 : 5;
        int milestone = percent - percent % interval;
        if (qqChannel) {
            // QQ 主动进度只保留三个里程碑，兼顾可见性与主动消息频率限制。
            milestone = Math.min(milestone, 75);
        }
        if (milestone < interval || milestone <= action.lastProgressPercent()) {
            return;
        }
        // 轮询跨过多个节点时只发送最新进度，避免补发消息淹没最终完成反馈。
        long milestoneBytes = Math.min(summary.totalBytes(), summary.totalBytes() * milestone / 100);
        String size = String.format(java.util.Locale.ROOT, "%.1f/%.1f MiB",
                milestoneBytes / 1048576.0, summary.totalBytes() / 1048576.0);
        messagingClient.reply(message.id(), "automation-progress-" + run.id() + "-"
                + action.id() + "-" + milestone, "下载进度：" + milestone + "%（" + size + "）。");
        repository.updateProgress(claim, action.id(), milestone);
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
        return transactionTemplate.execute(transactionStatus -> {
            AutomationRunView updated = repository.updateRunAggregate(messageId, status, finalError);
            repository.completeLinks(messageId, status);
            return updated;
        });
    }

    private AutomationRunView aggregate(AutomationRepository.RunClaim claim) {
        AutomationRunView run = repository.findRunById(claim.runId()).orElseThrow();
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
        return transactionTemplate.execute(transactionStatus -> {
            AutomationRunView updated = repository.updateRunAggregate(claim, status, finalError);
            repository.completeLinks(claim.messageId(), updated.status());
            return updated;
        });
    }

    private String mapActionStatus(String status) {
        if (status == null) {
            throw new IllegalStateException("Action status is missing");
        }
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

    private AutomationRunView noInput(AutomationRepository.RunClaim claim) {
        return transactionTemplate.execute(status -> {
            AutomationRunView updated = repository.updateRunAggregate(
                    claim, "FAILED", ErrorCode.NO_ACTION_INPUT.code());
            repository.completeLinks(claim.messageId(), updated.status());
            return updated;
        });
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
            String matched = matcher.group().replaceAll("[),.;]+$", "");
            if (MagnetLink.isMagnet(matched)) {
                try {
                    urls.add(MagnetLink.normalize(matched));
                } catch (IllegalArgumentException ignored) {
                    // 非法磁力链接不触发下载，也不把内部 tracker 当作独立网页。
                }
                continue;
            }
            boolean explicitScheme = matched.regionMatches(true, 0, "http://", 0, 7)
                    || matched.regionMatches(true, 0, "https://", 0, 8);
            // 无协议的裸候选若只是带扩展名的文件名，则不是可下载链接。
            if (!explicitScheme && looksLikeFileName(matched)) {
                continue;
            }
            String candidate = explicitScheme ? matched : "https://" + matched;
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

    /**
     * 判断无协议的裸候选是否只是带扩展名的文件名，而不是真实域名。
     */
    private boolean looksLikeFileName(String value) {
        // 主机名在路径、端口或查询串之前结束。
        int boundary = value.length();
        int slash = value.indexOf('/');
        if (slash >= 0 && slash < boundary) {
            boundary = slash;
        }
        int colon = value.indexOf(':');
        if (colon >= 0 && colon < boundary) {
            boundary = colon;
        }
        String host = value.substring(0, boundary);
        int dot = host.lastIndexOf('.');
        if (dot < 0 || dot == host.length() - 1) {
            return false;
        }
        return ATTACHMENT_FILE_EXTENSIONS.contains(host.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void replyDuplicateLinks(InboundMessage message, UUID runId,
                                     List<AutomationRepository.LinkClaim> duplicates,
                                     UUID duplicateEventId) {
        if (duplicates.isEmpty()) {
            return;
        }
        replyDuplicateFeedback(message.id(), runId, duplicateEventId);
    }

    private void replyDuplicateFeedback(UUID messageId, UUID runId, UUID duplicateEventId) {
        try {
            // 同一运行的同步快路径与 Outbox 使用完全相同的请求，安全重试不会重复发消息。
            messagingClient.reply(messageId, DuplicateLinkFeedback.idempotencyKey(runId),
                    DuplicateLinkFeedback.body());
        } catch (RuntimeException exception) {
            // 纯重复运行的持久化事件会继续重试；混合运行仍有可靠终态反馈。
            LOGGER.warn("Duplicate link reply failed: messageId={}, errorType={}",
                    messageId, exception.getClass().getSimpleName());
            return;
        }
        if (duplicateEventId == null) {
            return;
        }
        try {
            // 已明确获知 Messaging 接受后即可结束事件，避免后台再发一次 HTTP 请求。
            repository.markOutboxPublished(duplicateEventId);
        } catch (RuntimeException exception) {
            // 标记失败时保留事件，由后台使用相同幂等键安全确认。
            LOGGER.warn("Duplicate link outbox acknowledgement failed: eventId={}, errorType={}",
                    duplicateEventId, exception.getClass().getSimpleName());
        }
    }

    private String normalizeUrl(String value) {
        if (MagnetLink.isMagnet(value)) {
            return MagnetLink.normalize(value);
        }
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme().toLowerCase();
            String host = uri.getHost().toLowerCase();
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            if (("x.com".equals(host) || "twitter.com".equals(host)
                    || "www.x.com".equals(host) || "mobile.x.com".equals(host))) {
                host = "x.com";
                Matcher status = Pattern.compile(
                        "^/(?:[^/]+/status|i/(?:web/)?status)/([0-9]{1,24})(?:/.*)?$",
                        Pattern.CASE_INSENSITIVE).matcher(path);
                if (status.matches()) {
                    path = "/i/web/status/" + status.group(1);
                } else {
                    path = path.replaceFirst("/+$", "").replaceFirst("/media$", "");
                }
            }
            return new URI(scheme, null, host, port, path, uri.getRawQuery(), null).toASCIIString();
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException("Message URL is invalid", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String serializeBatch(List<String> urls) {
        try {
            return OBJECT_MAPPER.writeValueAsString(urls);
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
