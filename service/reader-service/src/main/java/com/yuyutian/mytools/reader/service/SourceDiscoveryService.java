package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateDiscoveryRequest;
import com.yuyutian.mytools.reader.model.DiscoveryRecord;
import com.yuyutian.mytools.reader.model.DiscoveryView;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.model.SourceIngestRequest;
import com.yuyutian.mytools.reader.model.SourceIngestResult;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * 书源发现任务编排与版本化写入服务。
 */
@Service
public class SourceDiscoveryService {

    private final DiscoveryRepository repository;
    private final TaskSchedulerClient schedulerClient;

    /**
     * 创建书源发现服务。
     *
     * @param repository 发现仓储
     * @param schedulerClient 调度客户端
     */
    public SourceDiscoveryService(DiscoveryRepository repository, TaskSchedulerClient schedulerClient) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 幂等创建书源发现任务。
     *
     * @param request 创建请求
     * @return 发现任务视图
     */
    @Transactional
    public DiscoveryView create(CreateDiscoveryRequest request) {
        DiscoveryRecord record = repository.findByIdempotencyKey(request.ownerId(), request.idempotencyKey())
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    DiscoveryRecord created = new DiscoveryRecord(UUID.randomUUID(), request.ownerId(),
                            request.idempotencyKey(), request.url(), "ACCEPTED", null,
                            0, 0, 0, now, now);
                    repository.insert(created);
                    return created;
                });
        if (!record.url().equals(request.url())) {
            throw new IllegalArgumentException("source discovery idempotency conflict");
        }
        if (record.taskId() == null) {
            Map<String, Object> parameters = Map.of(
                    "requestId", record.id().toString(), "ownerId", record.ownerId(), "url", record.url());
            UUID taskId = schedulerClient.createTask("reader_source_discovery",
                    "reader_source_discovery:" + record.id() + ":v1", "READER_SOURCE_DISCOVERY",
                    record.id(), 30, parameters);
            repository.bindTask(record.id(), taskId);
            record = required(record.id());
        }
        return view(record);
    }

    /**
     * 查询并同步书源发现执行摘要。
     *
     * @param requestId 请求标识
     * @return 发现任务视图
     */
    @Transactional
    public DiscoveryView get(UUID requestId) {
        DiscoveryRecord record = required(requestId);
        if (record.taskId() == null) {
            return view(record);
        }
        SchedulerResult schedulerResult = schedulerClient.getResults(record.taskId());
        var latest = schedulerResult.steps().stream()
                .filter(step -> "discover_sources".equals(step.stepName()))
                .max(Comparator.comparingInt(SchedulerResult.StepResult::attempt));
        int processed = record.processed();
        int saved = record.saved();
        int rejected = record.rejected();
        if (latest.isPresent() && "SUCCEEDED".equals(latest.get().status())) {
            Map<String, Object> result = latest.get().result();
            processed = number(result.get("processed"), processed);
            saved = number(result.get("saved"), saved);
            rejected = number(result.get("rejected"), rejected);
        }
        repository.updateSummary(requestId, schedulerResult.status(), processed, saved, rejected);
        return view(required(requestId));
    }

    /**
     * 按所有者查询书源发现任务。
     *
     * @param requestId 请求标识
     * @param ownerId 所有者标识
     * @return 发现任务视图
     */
    public DiscoveryView get(UUID requestId, long ownerId) {
        requiredOwner(requestId, ownerId);
        return get(requestId);
    }

    /**
     * 保存 Executor 已发现的一批书源。
     *
     * @param requestId 发现请求标识
     * @param request 批量写入请求
     * @return 写入结果
     */
    @Transactional
    public SourceIngestResult ingest(UUID requestId, SourceIngestRequest request) {
        required(requestId);
        return repository.saveSources(requestId, request.sources());
    }

    /**
     * 取消书源发现任务。
     *
     * @param requestId 请求标识
     * @return 发现任务视图
     */
    @Transactional
    public DiscoveryView cancel(UUID requestId) {
        DiscoveryRecord record = required(requestId);
        if (record.taskId() != null && !terminal(record.status())) {
            schedulerClient.cancel(record.taskId());
        }
        return get(requestId);
    }

    /**
     * 按所有者取消书源发现任务。
     *
     * @param requestId 请求标识
     * @param ownerId 所有者标识
     * @return 发现任务视图
     */
    public DiscoveryView cancel(UUID requestId, long ownerId) {
        requiredOwner(requestId, ownerId);
        return cancel(requestId);
    }

    private DiscoveryRecord required(UUID requestId) {
        return repository.findById(requestId).orElseThrow(() -> new DiscoveryNotFoundException(requestId));
    }

    private DiscoveryRecord requiredOwner(UUID requestId, long ownerId) {
        DiscoveryRecord record = required(requestId);
        if (record.ownerId() != ownerId) {
            throw new DiscoveryNotFoundException(requestId);
        }
        return record;
    }

    private DiscoveryView view(DiscoveryRecord record) {
        return new DiscoveryView(record.id(), record.taskId(), record.status(), record.url(), record.processed(),
                record.saved(), record.rejected(), record.createdAt(), record.updatedAt());
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : fallback;
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "TIMED_OUT".equals(status) || "CANCELLED".equals(status);
    }
}
