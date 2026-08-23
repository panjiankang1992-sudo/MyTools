package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.model.CreateEbookImportRequest;
import com.yuyutian.mytools.reader.model.EbookImportRecord;
import com.yuyutian.mytools.reader.model.EbookImportView;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import com.yuyutian.mytools.reader.repository.EbookImportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 书源电子书导入任务编排服务。
 */
@Service
public class EbookImportService {

    private final EbookImportRepository repository;
    private final DiscoveryRepository sourceRepository;
    private final TaskSchedulerClient schedulerClient;
    private final ReaderProperties properties;

    /**
     * 创建电子书导入服务。
     *
     * @param repository 导入仓储
     * @param sourceRepository 书源仓储
     * @param schedulerClient 调度客户端
     * @param properties 阅读服务配置
     */
    public EbookImportService(EbookImportRepository repository, DiscoveryRepository sourceRepository,
                              TaskSchedulerClient schedulerClient, ReaderProperties properties) {
        this.repository = repository;
        this.sourceRepository = sourceRepository;
        this.schedulerClient = schedulerClient;
        this.properties = properties;
    }

    /**
     * 幂等创建书源电子书导入任务。
     *
     * @param request 创建请求
     * @return 导入视图
     */
    @Transactional
    public EbookImportView create(CreateEbookImportRequest request) {
        EbookImportRecord record = repository.findByIdempotencyKey(request.ownerId(), request.idempotencyKey())
                .orElseGet(() -> createRecord(request));
        if (record.taskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_import_ebook",
                    "reader_import_ebook:" + record.id() + ":v1", "READER_EBOOK_IMPORT",
                    record.id(), 50, record.parameters());
            repository.bindTask(record.id(), taskId);
            record = required(record.id());
        }
        return view(record);
    }

    /**
     * 查询并同步电子书导入结果。
     *
     * @param requestId 请求标识
     * @return 导入视图
     */
    @Transactional
    public EbookImportView get(UUID requestId) {
        EbookImportRecord record = required(requestId);
        if (record.taskId() == null) {
            return view(record);
        }
        SchedulerResult schedulerResult = schedulerClient.getResults(record.taskId());
        if ("SUCCEEDED".equals(schedulerResult.status())) {
            Map<String, Object> result = schedulerResult.steps().stream()
                    .filter(step -> "import_ebook".equals(step.stepName()) && "SUCCEEDED".equals(step.status()))
                    .max(Comparator.comparingInt(SchedulerResult.StepResult::attempt))
                    .map(SchedulerResult.StepResult::result)
                    .orElseThrow(() -> new IllegalStateException("Successful ebook task has no result"));
            repository.succeed(record, result);
        } else {
            repository.updateStatus(requestId, schedulerResult.status());
        }
        return view(required(requestId));
    }

    /**
     * 取消电子书导入任务。
     *
     * @param requestId 请求标识
     * @return 导入视图
     */
    @Transactional
    public EbookImportView cancel(UUID requestId) {
        EbookImportRecord record = required(requestId);
        if (record.taskId() != null && !terminal(record.status())) {
            schedulerClient.cancel(record.taskId());
        }
        return get(requestId);
    }

    private EbookImportRecord createRecord(CreateEbookImportRequest request) {
        var source = sourceRepository.findExecutionSnapshot(request.ownerId(), request.sourceId())
                .orElseThrow(() -> new EbookSourceNotFoundException(request.sourceId()));
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("requestId", id.toString());
        parameters.put("ownerId", request.ownerId());
        parameters.put("sourceId", source.id().toString());
        parameters.put("sourceUrl", source.sourceUrl());
        parameters.put("sourceSnapshot", source.snapshot());
        parameters.put("bookUrl", request.bookUrl());
        parameters.put("title", request.title());
        parameters.put("author", request.author() == null ? "" : request.author());
        parameters.put("storageRoot", properties.ebookStorageRoot());
        EbookImportRecord record = new EbookImportRecord(id, request.ownerId(), request.idempotencyKey(),
                source.id(), source.version(), request.bookUrl(), request.title(), request.author(),
                properties.ebookStorageRoot(), "ACCEPTED", null, parameters,
                null, null, null, null, now, now);
        repository.insert(record);
        return record;
    }

    private EbookImportRecord required(UUID requestId) {
        return repository.findById(requestId).orElseThrow(() -> new EbookImportNotFoundException(requestId));
    }

    private EbookImportView view(EbookImportRecord record) {
        return new EbookImportView(record.id(), record.taskId(), record.status(), record.sourceId(),
                record.sourceVersion(), record.title(), record.author(), record.chapterCount(), record.outputSize(),
                record.outputSha256(), record.storageUri(), record.createdAt(), record.updatedAt());
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "TIMED_OUT".equals(status) || "CANCELLED".equals(status);
    }
}
