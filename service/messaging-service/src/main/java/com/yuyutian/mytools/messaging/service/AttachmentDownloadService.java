package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.AttachmentDownloadRecord;
import com.yuyutian.mytools.messaging.model.AttachmentDownloadView;
import com.yuyutian.mytools.messaging.model.ExecuteAttachmentDownloadResult;
import com.yuyutian.mytools.messaging.model.ResolveAttachmentResult;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.OutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * 消息附件下载任务编排服务。
 */
@Service
public class AttachmentDownloadService {

    private final MessagingRepository repository;
    private final TaskSchedulerClient schedulerClient;
    private final DownloadIngestionClient downloadClient;
    private final ProviderFileResolverClient resolverClient;
    private final EmailAttachmentContentService emailAttachmentContentService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建消息附件下载编排服务。
     */
    public AttachmentDownloadService(MessagingRepository repository, TaskSchedulerClient schedulerClient,
                                     DownloadIngestionClient downloadClient,
                                     ProviderFileResolverClient resolverClient,
                                     EmailAttachmentContentService emailAttachmentContentService,
                                     TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
        this.downloadClient = downloadClient;
        this.resolverClient = resolverClient;
        this.emailAttachmentContentService = emailAttachmentContentService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 幂等创建一个附件下载处理任务。
     */
    public AttachmentDownloadView create(UUID messageId, UUID partId) {
        MessagingRepository.AttachmentSource source = requiredSource(messageId, partId);
        if ("FORWARD_ERROR".equals(source.attachmentType())) {
            // 失败占位只创建本地终态作业，让自动化产生可见失败项，绝不提交下载或解析任务。
            AttachmentDownloadRecord failure = transactionTemplate.execute(status -> repository
                    .findAttachmentJobByPart(partId).orElseGet(() -> insertForwardFailure(messageId, partId)));
            if (failure == null) {
                throw new IllegalStateException("Attachment transaction returned no record");
            }
            return view(failure);
        }
        validateDownloadable(source);
        AttachmentDownloadRecord record = transactionTemplate.execute(status -> repository
                .findAttachmentJobByPart(partId).orElseGet(() -> insert(messageId, partId)));
        if (record == null) {
            throw new IllegalStateException("Attachment transaction returned no record");
        }
        // 已有公开 URL 时直接进入下载接入，省去附件包装任务及其对账子任务。
        if (record.downloadRequestId() == null && !cancellationRequested(record.status())
                && !terminal(record.status()) && isHttp(source.sourceUrl())) {
            UUID downloadId = downloadClient.createHttpAttachment(record.id(), source.ownerId(), source.partId(),
                    source.sourceUrl(), safeFileName(source), source.mimeType(), source.declaredSize(),
                    source.receivedAt());
            UUID jobId = record.id();
            Boolean bindingAccepted = transactionTemplate.execute(
                    status -> repository.bindDownloadRequest(jobId, downloadId));
            record = required(jobId);
            if (!Boolean.TRUE.equals(bindingAccepted)) {
                // 外部资源已经创建但本地状态拒绝启动时，必须立即补偿取消以避免孤儿下载。
                cancelBoundDownload(record, source.ownerId());
                return view(required(jobId));
            }
        }
        if (record.downloadRequestId() == null && record.taskId() == null
                && !cancellationRequested(record.status()) && !terminal(record.status())) {
            UUID taskId = schedulerClient.createAttachmentDownloadTask(record.id());
            UUID jobId = record.id();
            transactionTemplate.executeWithoutResult(status -> repository.bindAttachmentTask(jobId, taskId));
            record = required(record.id());
            if (cancellationRequested(record.status())) {
                schedulerClient.cancel(taskId);
                record = required(jobId);
            }
        }
        if (record.downloadRequestId() != null && cancellationCompensationRequired(record.status())) {
            // 重试创建时继续收敛此前失败的取消补偿。
            cancelBoundDownload(record, source.ownerId());
            record = required(record.id());
        }
        return view(record);
    }

    /**
     * 为指定所有者创建附件下载任务。
     *
     * @param messageId 消息标识
     * @param partId 分段标识
     * @param ownerId 所有者
     * @return 附件任务
     */
    public AttachmentDownloadView create(UUID messageId, UUID partId, long ownerId) {
        requireOwner(messageId, partId, ownerId);
        return create(messageId, partId);
    }

    /**
     * 查询一个附件下载处理任务。
     */
    public AttachmentDownloadView get(UUID jobId) {
        AttachmentDownloadRecord current = required(jobId);
        if (current.downloadRequestId() == null && current.taskId() != null && !terminal(current.status())) {
            String taskStatus = schedulerClient.status(current.taskId());
            if ("FAILED".equals(taskStatus) || "TIMED_OUT".equals(taskStatus)
                    || "CANCELLED".equals(taskStatus)) {
                String status = "CANCELLED".equals(taskStatus) ? "CANCELLED" : "FAILED";
                transactionTemplate.executeWithoutResult(transaction -> repository.updateAttachmentJobStatus(
                        jobId, status, "ATTACHMENT_TASK_" + taskStatus));
                current = required(jobId);
            }
        }
        if (current.downloadRequestId() == null || terminal(current.status())) {
            return view(current);
        }
        MessagingRepository.AttachmentSource source = requiredSource(current.messageId(), current.partId());
        DownloadIngestionClient.DownloadSnapshot snapshot = downloadClient.get(
                current.downloadRequestId(), source.ownerId());
        String reconciled = mapDownloadStatus(snapshot.status());
        if (!reconciled.equals(current.status())) {
            String errorCode = "TIMED_OUT".equals(snapshot.status())
                    ? "DOWNLOAD_TIMED_OUT" : "FAILED".equals(reconciled) ? "DOWNLOAD_FAILED" : null;
            transactionTemplate.executeWithoutResult(status ->
                    repository.updateAttachmentJobStatus(jobId, reconciled, errorCode));
            current = required(jobId);
        }
        return view(current);
    }

    /** 按所有者查询附件任务。 @param jobId 作业 @param ownerId 所有者 @return 附件任务 */
    public AttachmentDownloadView get(UUID jobId,long ownerId) {
        AttachmentDownloadRecord record=required(jobId);requireOwner(record.messageId(),record.partId(),ownerId);return get(jobId);
    }

    /**
     * 请求取消所有者的附件任务。
     *
     * @param jobId 作业标识
     * @param ownerId 所有者标识
     * @return 附件任务最新状态
     */
    public AttachmentDownloadView cancel(UUID jobId, long ownerId) {
        AttachmentDownloadRecord record = required(jobId);
        requireOwner(record.messageId(), record.partId(), ownerId);
        if (terminal(record.status()) && record.downloadRequestId() == null) {
            return view(record);
        }
        // 取消意图必须先落库，使并发绑定和迟到执行都能观察并收敛。
        transactionTemplate.executeWithoutResult(transaction -> repository.requestAttachmentCancellation(jobId));
        record = required(jobId);
        if (record.downloadRequestId() != null) {
            cancelBoundDownload(record, ownerId);
            return view(required(jobId));
        }
        if (record.taskId() != null) {
            // 尚未创建实际下载时，仅取消仍在执行的包装 Scheduler 任务。
            schedulerClient.cancel(record.taskId());
        }
        // 包装任务取消期间可能刚好完成了实际下载绑定，重读后必须转而取消真实下载。
        record = required(jobId);
        if (record.downloadRequestId() != null && !terminal(record.status())) {
            cancelBoundDownload(record, ownerId);
        }
        return view(required(jobId));
    }

    /**
     * 由 Executor 创建实际 Download Ingestion 子任务。
     */
    public ExecuteAttachmentDownloadResult execute(UUID jobId) {
        AttachmentDownloadRecord job = required(jobId);
        if (job.downloadRequestId() != null) {
            if (cancellationCompensationRequired(job.status())) {
                MessagingRepository.AttachmentSource source = requiredSource(job.messageId(), job.partId());
                cancelBoundDownload(job, source.ownerId());
                job = required(jobId);
            }
            return new ExecuteAttachmentDownloadResult(job.id(), job.downloadRequestId(), job.status());
        }
        if (cancellationRequested(job.status()) || terminal(job.status())) {
            return new ExecuteAttachmentDownloadResult(job.id(), job.downloadRequestId(), job.status());
        }
        MessagingRepository.AttachmentSource source = requiredSource(job.messageId(), job.partId());
        UUID downloadId;
        if ("STREAM".equals(source.resolutionMode())) {
            downloadId = downloadClient.createStreamedAttachment(job.id(), source.ownerId(), source.partId(),
                    safeFileName(source), source.mimeType(), source.declaredSize(), source.receivedAt());
        } else {
            String sourceUrl = effectiveSourceUrl(source);
            downloadId = downloadClient.createHttpAttachment(job.id(), source.ownerId(), source.partId(),
                    sourceUrl, safeFileName(source), source.mimeType(), source.declaredSize(), source.receivedAt());
        }
        UUID currentJobId = job.id();
        Boolean bindingAccepted = transactionTemplate.execute(
                status -> repository.bindDownloadRequest(currentJobId, downloadId));
        AttachmentDownloadRecord updated = required(currentJobId);
        if (!Boolean.TRUE.equals(bindingAccepted)) {
            // 取消或终态抢先落库时，刚创建的外部下载必须执行补偿取消。
            cancelBoundDownload(updated, source.ownerId());
            updated = required(currentJobId);
        }
        return new ExecuteAttachmentDownloadResult(updated.id(), updated.downloadRequestId(), updated.status());
    }

    /**
     * 幂等解析 provider 文件引用，解析结果仅保存在消息 schema。
     */
    public ResolveAttachmentResult resolve(UUID jobId) {
        AttachmentDownloadRecord job = required(jobId);
        if (cancellationRequested(job.status()) || terminal(job.status())) {
            return new ResolveAttachmentResult(jobId, job.status(), false);
        }
        MessagingRepository.AttachmentSource source = requiredSource(job.messageId(), job.partId());
        if (isHttp(source.sourceUrl()) || isHttp(source.resolvedSourceUrl())
                || "STREAM".equals(source.resolutionMode())) {
            return new ResolveAttachmentResult(jobId, job.status(), true);
        }
        if ("EMAIL".equals(source.channelType())
                && emailAttachmentContentService.supports(source.providerFileId(), source.providerAccountKey())) {
            transactionTemplate.executeWithoutResult(status -> repository.bindResolvedSource(jobId, "STREAM", null));
            return new ResolveAttachmentResult(jobId, required(jobId).status(), true);
        }
        if (!("ONEBOT".equals(source.channelType()) || "TELEGRAM".equals(source.channelType()))
                || source.providerAccountKey() == null || source.providerAccountKey().isBlank()
                || source.providerFileId() == null || source.providerFileId().isBlank()
                || source.attachmentType() == null) {
            throw new AttachmentDownloadInvalidException();
        }
        ProviderFileResolverClient.Resolution resolution = resolverClient.resolve(source.channelType(),
                source.providerAccountKey(), source.attachmentType(),
                source.providerFileId());
        transactionTemplate.executeWithoutResult(status -> repository.bindResolvedSource(jobId,
                resolution.mode(), resolution.downloadUrl()));
        AttachmentDownloadRecord updated = required(jobId);
        return new ResolveAttachmentResult(jobId, updated.status(), !cancellationRequested(updated.status())
                && !terminal(updated.status()));
    }

    /**
     * 将 STREAM 模式的 provider 内容转发给受控下载执行器。
     *
     * @param jobId 附件作业标识
     * @param output HTTP 响应输出流
     */
    public void stream(UUID jobId, OutputStream output) {
        AttachmentDownloadRecord job = required(jobId);
        MessagingRepository.AttachmentSource source = requiredSource(job.messageId(), job.partId());
        if (!"STREAM".equals(source.resolutionMode())) {
            throw new AttachmentDownloadInvalidException();
        }
        if ("EMAIL".equals(source.channelType())) {
            emailAttachmentContentService.stream(source.providerFileId(), source.providerAccountKey(), output,
                    byteLimit(source.declaredSize()));
            return;
        }
        if (!("ONEBOT".equals(source.channelType()) || "TELEGRAM".equals(source.channelType()))) {
            throw new AttachmentDownloadInvalidException();
        }
        resolverClient.stream(source.channelType(), source.providerAccountKey(), source.attachmentType(),
                source.providerFileId(), output, byteLimit(source.declaredSize()));
    }

    private AttachmentDownloadRecord insert(UUID messageId, UUID partId) {
        Instant now = Instant.now();
        AttachmentDownloadRecord record = new AttachmentDownloadRecord(UUID.randomUUID(), messageId, partId,
                "ACCEPTED", null, null, null, now, now);
        repository.insertAttachmentJob(record);
        return record;
    }

    private AttachmentDownloadRecord insertForwardFailure(UUID messageId, UUID partId) {
        Instant now = Instant.now();
        AttachmentDownloadRecord record = new AttachmentDownloadRecord(UUID.randomUUID(), messageId, partId,
                "FAILED", null, null, "ONEBOT_FORWARD_UNAVAILABLE", now, now);
        repository.insertAttachmentJob(record);
        return record;
    }

    private MessagingRepository.AttachmentSource requiredSource(UUID messageId, UUID partId) {
        return repository.findAttachmentSource(messageId, partId)
                .orElseThrow(AttachmentDownloadNotFoundException::new);
    }

    private void requireOwner(UUID messageId,UUID partId,long ownerId) {
        if(requiredSource(messageId,partId).ownerId()!=ownerId)throw new AttachmentDownloadNotFoundException();
    }

    private AttachmentDownloadRecord required(UUID jobId) {
        return repository.findAttachmentJob(jobId).orElseThrow(AttachmentDownloadNotFoundException::new);
    }

    private void validateDownloadable(MessagingRepository.AttachmentSource source) {
        boolean direct = isHttp(source.sourceUrl());
        boolean resolvable = source.providerFileId() != null && !source.providerFileId().isBlank()
                && source.providerAccountKey() != null && !source.providerAccountKey().isBlank()
                && ("ONEBOT".equals(source.channelType()) || "TELEGRAM".equals(source.channelType())
                    || "EMAIL".equals(source.channelType()));
        if (!"ATTACHMENT".equals(source.partType()) || !(direct || resolvable)) {
            throw new AttachmentDownloadInvalidException();
        }
    }

    private String effectiveSourceUrl(MessagingRepository.AttachmentSource source) {
        if (isHttp(source.sourceUrl())) {
            return source.sourceUrl();
        }
        if (isHttp(source.resolvedSourceUrl())) {
            return source.resolvedSourceUrl();
        }
        throw new AttachmentDownloadInvalidException();
    }

    private boolean isHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            // DNS、公网地址及重定向仍由下载执行器逐跳校验，此处仅接受无凭据的绝对 HTTP URI。
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private long byteLimit(Long declaredSize) {
        return DownloadIngestionClient.maximumBytes(declaredSize);
    }

    private String safeFileName(MessagingRepository.AttachmentSource source) {
        String name = source.fileName();
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.length() > 255 || ".".equals(name) || "..".equals(name)) {
            return "message-attachment-" + source.partId() + ".bin";
        }
        return name;
    }

    private AttachmentDownloadView view(AttachmentDownloadRecord record) {
        return new AttachmentDownloadView(record.id(), record.messageId(), record.partId(), record.status(),
                record.taskId(), record.downloadRequestId(), record.lastErrorCode(), record.createdAt(),
                record.updatedAt());
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean cancellationRequested(String status) {
        return "CANCELLING".equals(status);
    }

    private boolean cancellationCompensationRequired(String status) {
        return "CANCELLING".equals(status) || terminal(status);
    }

    private void cancelBoundDownload(AttachmentDownloadRecord record, long ownerId) {
        // 实际下载已绑定后由 Download Ingestion 负责取消，包装任务不再拥有下载生命周期。
        DownloadIngestionClient.DownloadSnapshot snapshot =
                downloadClient.cancel(record.downloadRequestId(), ownerId);
        CancellationState cancellation = mapCancellationStatus(snapshot.status());
        transactionTemplate.executeWithoutResult(transaction -> repository.updateAttachmentCancellationResult(
                record.id(), cancellation.status(), cancellation.errorCode()));
    }

    private CancellationState mapCancellationStatus(String status) {
        return switch (status) {
            case "ACCEPTED", "PLANNING", "RUNNING", "CANCELLING" ->
                    new CancellationState("CANCELLING", null);
            case "SUCCEEDED" -> new CancellationState("SUCCEEDED", null);
            case "FAILED" -> new CancellationState("FAILED", "DOWNLOAD_FAILED");
            case "TIMED_OUT" -> new CancellationState("FAILED", "DOWNLOAD_TIMED_OUT");
            case "CANCELLED" -> new CancellationState("CANCELLED", null);
            default -> throw new IllegalStateException("Download Ingestion returned an unsupported cancel status");
        };
    }

    private String mapDownloadStatus(String status) {
        return switch (status) {
            case "ACCEPTED", "PLANNING", "RUNNING", "CANCELLING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED", "TIMED_OUT" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> throw new IllegalStateException("Download Ingestion returned an unsupported status");
        };
    }

    private record CancellationState(String status, String errorCode) {
    }
}
