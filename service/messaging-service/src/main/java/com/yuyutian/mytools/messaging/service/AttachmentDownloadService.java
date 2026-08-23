package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.AttachmentDownloadRecord;
import com.yuyutian.mytools.messaging.model.AttachmentDownloadView;
import com.yuyutian.mytools.messaging.model.ExecuteAttachmentDownloadResult;
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
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建消息附件下载编排服务。
     */
    public AttachmentDownloadService(MessagingRepository repository, TaskSchedulerClient schedulerClient,
                                     DownloadIngestionClient downloadClient,
                                     TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
        this.downloadClient = downloadClient;
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
        validateDownloadable(source);
        UUID downloadId = downloadClient.createHttpAttachment(job.id(), source.ownerId(), source.partId(),
                source.sourceUrl(), safeFileName(source), source.declaredSize());
        transactionTemplate.executeWithoutResult(status -> repository.bindDownloadRequest(job.id(), downloadId));
        AttachmentDownloadRecord updated = required(job.id());
        return new ExecuteAttachmentDownloadResult(updated.id(), updated.downloadRequestId(), updated.status());
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
        if (!"ATTACHMENT".equals(source.partType()) || source.sourceUrl() == null
                || !(source.sourceUrl().startsWith("https://") || source.sourceUrl().startsWith("http://"))) {
            throw new AttachmentDownloadInvalidException();
        }
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
