package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ChapterCacheBatchRequest;
import com.yuyutian.mytools.reader.model.ChapterCacheView;
import com.yuyutian.mytools.reader.model.ChapterPrefetchRecord;
import com.yuyutian.mytools.reader.model.ChapterPrefetchView;
import com.yuyutian.mytools.reader.model.CreateChapterPrefetchRequest;
import com.yuyutian.mytools.reader.repository.ChapterCacheRepository;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/**
 * 章节预取任务编排与缓存查询服务。
 */
@Service
public class ChapterPrefetchService {

    private final ChapterCacheRepository repository;
    private final DiscoveryRepository sourceRepository;
    private final TaskSchedulerClient schedulerClient;

    /**
     * 创建章节预取服务。
     */
    public ChapterPrefetchService(ChapterCacheRepository repository, DiscoveryRepository sourceRepository,
                                  TaskSchedulerClient schedulerClient) {
        this.repository = repository;
        this.sourceRepository = sourceRepository;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 幂等创建章节预取任务。
     */
    @Transactional
    public ChapterPrefetchView create(CreateChapterPrefetchRequest request) {
        ChapterPrefetchRecord record = repository.findByIdempotencyKey(request.ownerId(), request.idempotencyKey())
                .orElseGet(() -> createRecord(request));
        if (record.taskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_prefetch_chapters",
                    "reader_prefetch_chapters:" + record.id() + ":v1", "READER_CHAPTER_PREFETCH",
                    record.id(), 50, record.parameters());
            repository.bindTask(record.id(), taskId);
            record = required(record.id());
        }
        return view(record);
    }

    /**
     * 查询并同步预取任务状态。
     */
    @Transactional
    public ChapterPrefetchView get(UUID requestId) {
        ChapterPrefetchRecord record = required(requestId);
        if (record.taskId() != null) {
            repository.updateSummary(requestId, schedulerClient.getResults(record.taskId()).status());
            record = required(requestId);
        }
        return view(record);
    }

    /**
     * 取消章节预取任务。
     */
    @Transactional
    public ChapterPrefetchView cancel(UUID requestId) {
        ChapterPrefetchRecord record = required(requestId);
        if (record.taskId() != null && !terminal(record.status())) {
            schedulerClient.cancel(record.taskId());
        }
        return get(requestId);
    }

    /**
     * 保存执行器提交的章节缓存批次。
     */
    @Transactional
    public int saveBatch(UUID requestId, ChapterCacheBatchRequest request) {
        ChapterPrefetchRecord record = required(requestId);
        for (ChapterCacheBatchRequest.Chapter chapter : request.chapters()) {
            // 内容字节数和摘要均由服务端复核，不能信任脚本声明值。
            byte[] content = chapter.content().getBytes(StandardCharsets.UTF_8);
            if (content.length != chapter.sizeBytes()
                    || !DigestSupport.sha256(content).equalsIgnoreCase(chapter.sha256())) {
                throw new ChapterCacheInvalidException();
            }
        }
        int saved = repository.saveBatch(record, request);
        repository.updateSummary(requestId, record.status());
        return saved;
    }

    /**
     * 查询未过期的章节缓存。
     */
    public ChapterCacheView cached(long ownerId, UUID sourceId, String bookUrl, String chapterUrl) {
        return repository.findCached(ownerId, sourceId, bookUrl, chapterUrl)
                .orElseThrow(ChapterCacheNotFoundException::new);
    }

    private ChapterPrefetchRecord createRecord(CreateChapterPrefetchRequest request) {
        var source = sourceRepository.findExecutionSnapshot(request.ownerId(), request.sourceId())
                .orElseThrow(() -> new EbookSourceNotFoundException(request.sourceId()));
        var indexes = new LinkedHashSet<>(request.chapterIndexes());
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("requestId", id.toString());
        parameters.put("ownerId", request.ownerId());
        parameters.put("sourceId", source.id().toString());
        parameters.put("sourceUrl", source.sourceUrl());
        parameters.put("sourceSnapshot", source.snapshot());
        parameters.put("bookUrl", request.bookUrl());
        parameters.put("chapterIndexes", indexes);
        ChapterPrefetchRecord record = new ChapterPrefetchRecord(id, request.ownerId(), request.idempotencyKey(),
                source.id(), source.version(), request.bookUrl(), "ACCEPTED", null, parameters,
                indexes.size(), 0, now, now);
        repository.insert(record);
        return record;
    }

    private ChapterPrefetchRecord required(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ChapterPrefetchNotFoundException(id));
    }

    private ChapterPrefetchView view(ChapterPrefetchRecord record) {
        return new ChapterPrefetchView(record.id(), record.taskId(), record.status(), record.requestedCount(),
                record.cachedCount(), record.createdAt(), record.updatedAt());
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "TIMED_OUT".equals(status)
                || "CANCELLED".equals(status);
    }
}
