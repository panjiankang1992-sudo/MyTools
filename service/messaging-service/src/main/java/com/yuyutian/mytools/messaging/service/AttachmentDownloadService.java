package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.AttachmentDownloadRecord;
import com.yuyutian.mytools.messaging.model.AttachmentDownloadView;
import com.yuyutian.mytools.messaging.model.ExecuteAttachmentDownloadResult;
import com.yuyutian.mytools.messaging.model.ResolveAttachmentResult;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建消息附件下载编排服务。
     */
    public AttachmentDownloadService(MessagingRepository repository, TaskSchedulerClient schedulerClient,
                                     DownloadIngestionClient downloadClient,
                                     ProviderFileResolverClient resolverClient,
                                     TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
        this.downloadClient = downloadClient;
        this.resolverClient = resolverClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 幂等创建一个附件下载处理任务。
     */
    public AttachmentDownloadView create(UUID messageId, UUID partId) {
        MessagingRepository.AttachmentSource source = requiredSource(messageId, partId);
        validateDownloadable(source);
        AttachmentDownloadRecord record = transactionTemplate.execute(status -> repository
                .findAttachmentJobByPart(partId).orElseGet(() -> insert(messageId, partId)));
        if (record == null) {
            throw new IllegalStateException("Attachment transaction returned no record");
        }
        if (record.taskId() == null) {
            UUID taskId = schedulerClient.createAttachmentDownloadTask(record.id());
            UUID jobId = record.id();
            transactionTemplate.executeWithoutResult(status -> repository.bindAttachmentTask(jobId, taskId));
            record = required(record.id());
        }
        return view(record);
    }

    /**
     * 查询一个附件下载处理任务。
     */
    public AttachmentDownloadView get(UUID jobId) {
        AttachmentDownloadRecord current = required(jobId);
        if (current.downloadRequestId() == null || terminal(current.status())) {
            return view(current);
        }
        DownloadIngestionClient.DownloadSnapshot snapshot = downloadClient.get(current.downloadRequestId());
        String reconciled = mapDownloadStatus(snapshot.status());
        if (!reconciled.equals(current.status())) {
            String errorCode = "FAILED".equals(reconciled) ? "DOWNLOAD_FAILED" : null;
            transactionTemplate.executeWithoutResult(status ->
                    repository.updateAttachmentJobStatus(jobId, reconciled, errorCode));
            current = required(jobId);
        }
        return view(current);
    }

    /**
     * 由 Executor 创建实际 Download Ingestion 子任务。
     */
    public ExecuteAttachmentDownloadResult execute(UUID jobId) {
        AttachmentDownloadRecord job = required(jobId);
        if (job.downloadRequestId() != null) {
            return new ExecuteAttachmentDownloadResult(job.id(), job.downloadRequestId(), job.status());
        }
        MessagingRepository.AttachmentSource source = requiredSource(job.messageId(), job.partId());
        String sourceUrl = effectiveSourceUrl(source);
        UUID downloadId = downloadClient.createHttpAttachment(job.id(), source.ownerId(), source.partId(),
                sourceUrl, safeFileName(source), source.declaredSize());
        transactionTemplate.executeWithoutResult(status -> repository.bindDownloadRequest(job.id(), downloadId));
        AttachmentDownloadRecord updated = required(job.id());
        return new ExecuteAttachmentDownloadResult(updated.id(), updated.downloadRequestId(), updated.status());
    }

    /**
     * 幂等解析 provider 文件引用，解析结果仅保存在消息 schema。
     */
    public ResolveAttachmentResult resolve(UUID jobId) {
        AttachmentDownloadRecord job = required(jobId);
        MessagingRepository.AttachmentSource source = requiredSource(job.messageId(), job.partId());
        if (isHttp(source.sourceUrl()) || isHttp(source.resolvedSourceUrl())) {
            return new ResolveAttachmentResult(jobId, job.status(), true);
        }
        if (!"ONEBOT".equals(source.channelType())
                || source.providerAccountKey() == null || source.providerAccountKey().isBlank()
                || source.providerFileId() == null || source.providerFileId().isBlank()
                || source.attachmentType() == null) {
            throw new AttachmentDownloadInvalidException();
        }
        String resolvedUrl = resolverClient.resolve(source.providerAccountKey(), source.attachmentType(),
                source.providerFileId());
        transactionTemplate.executeWithoutResult(status -> repository.bindResolvedSource(jobId, resolvedUrl));
        return new ResolveAttachmentResult(jobId, required(jobId).status(), true);
    }

    private AttachmentDownloadRecord insert(UUID messageId, UUID partId) {
        Instant now = Instant.now();
        AttachmentDownloadRecord record = new AttachmentDownloadRecord(UUID.randomUUID(), messageId, partId,
                "ACCEPTED", null, null, null, now, now);
        repository.insertAttachmentJob(record);
        return record;
    }

    private MessagingRepository.AttachmentSource requiredSource(UUID messageId, UUID partId) {
        return repository.findAttachmentSource(messageId, partId)
                .orElseThrow(AttachmentDownloadNotFoundException::new);
    }

    private AttachmentDownloadRecord required(UUID jobId) {
        return repository.findAttachmentJob(jobId).orElseThrow(AttachmentDownloadNotFoundException::new);
    }

    private void validateDownloadable(MessagingRepository.AttachmentSource source) {
        boolean direct = isHttp(source.sourceUrl());
        boolean resolvable = source.providerFileId() != null && !source.providerFileId().isBlank()
                && source.providerAccountKey() != null && !source.providerAccountKey().isBlank()
                && "ONEBOT".equals(source.channelType());
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
        return value != null && (value.startsWith("https://") || value.startsWith("http://"));
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

    private String mapDownloadStatus(String status) {
        return switch (status) {
            case "ACCEPTED", "PLANNING", "RUNNING", "CANCELLING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> throw new IllegalStateException("Download Ingestion returned an unsupported status");
        };
    }
}
