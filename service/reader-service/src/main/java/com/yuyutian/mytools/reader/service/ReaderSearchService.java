package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateSearchRequest;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.model.SearchRecord;
import com.yuyutian.mytools.reader.model.SearchView;
import com.yuyutian.mytools.reader.repository.SearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 书源搜索创建、任务绑定和结果聚合服务。
 */
@Service
public class ReaderSearchService {

    private final SearchRepository repository;
    private final TaskSchedulerClient schedulerClient;

    /**
     * 创建书源搜索服务。
     *
     * @param repository 搜索仓储
     * @param schedulerClient 调度客户端
     */
    public ReaderSearchService(SearchRepository repository, TaskSchedulerClient schedulerClient) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 幂等创建并提交书源搜索任务。
     *
     * @param request 创建请求
     * @return 搜索视图
     */
    @Transactional
    public SearchView create(CreateSearchRequest request) {
        String scopedKey = scopedKey(request.ownerId(), request.idempotencyKey());
        SearchRecord record = repository.findByIdempotencyKey(scopedKey).orElseGet(() -> {
            Instant now = Instant.now();
            Map<String, Object> parameters = parameters(request);
            SearchRecord created = new SearchRecord(UUID.randomUUID(), request.ownerId(), scopedKey,
                    request.keyword(), request.mode(), request.page(), "ACCEPTED", null, parameters, now, now);
            repository.insert(created);
            return created;
        });
        if (record.ownerId() != request.ownerId() || !record.keyword().equals(request.keyword())
                || record.mode() != request.mode() || record.page() != request.page()) {
            throw new IllegalArgumentException("reader search idempotency conflict");
        }
        if (record.taskId() == null) {
            UUID taskId = schedulerClient.createSearchTask(
                    "reader_source_search:" + record.id() + ":reader-search-v2", record.id(), record.parameters());
            repository.bindTask(record.id(), taskId, "QUEUED");
            record = repository.findById(record.id()).orElseThrow();
        }
        return basicView(record, List.of(), 0, 0, 0);
    }

    /**
     * 查询并增量聚合调度结果。
     *
     * @param requestId 搜索请求标识
     * @return 搜索聚合视图
     */
    @Transactional
    public SearchView get(UUID requestId) {
        SearchRecord record = required(requestId);
        if (record.taskId() == null) {
            return basicView(record, repository.findAggregate(requestId), 0, 0, 0);
        }
        SchedulerResult schedulerResult = schedulerClient.getResults(record.taskId());
        Map<UUID, SchedulerResult.StepResult> latest = latestAttempts(schedulerResult.steps());
        LinkedHashMap<String, Map<String, Object>> aggregate = new LinkedHashMap<>();
        int completed = 0;
        int failed = 0;
        int total = 0;
        for (SchedulerResult.StepResult step : latest.values()) {
            repository.saveShard(requestId, step.executionId(), step.targetIndex(), step.targetCount(),
                    step.status(), step.result());
            total = Math.max(total, step.targetCount() == null ? 1 : step.targetCount());
            if ("SUCCEEDED".equals(step.status())) {
                completed++;
                for (Map<String, Object> result : resultRows(step.result())) {
                    aggregate.putIfAbsent(canonicalKey(result.get("name")), result);
                }
            } else if (isTerminal(step.status())) {
                failed++;
            }
        }
        String status = aggregateStatus(schedulerResult.status(), completed, failed);
        List<Map<String, Object>> results = new ArrayList<>(aggregate.values());
        repository.replaceAggregate(requestId, status, results);
        SearchRecord updated = required(requestId);
        return basicView(updated, results, completed, failed, total);
    }

    /**
     * 按所有者查询搜索。
     *
     * @param requestId 搜索标识
     * @param ownerId 所有者
     * @return 搜索
     */
    public SearchView get(UUID requestId, long ownerId) {
        requiredOwner(requestId, ownerId);
        return get(requestId);
    }

    /**
     * 取消搜索任务。
     *
     * @param requestId 搜索请求标识
     * @return 搜索视图
     */
    @Transactional
    public SearchView cancel(UUID requestId) {
        SearchRecord record = required(requestId);
        if (record.taskId() != null && !isTerminal(record.status())) {
            schedulerClient.cancel(record.taskId());
        }
        return get(requestId);
    }

    /**
     * 按所有者取消搜索。
     *
     * @param requestId 搜索标识
     * @param ownerId 所有者
     * @return 搜索
     */
    public SearchView cancel(UUID requestId, long ownerId) {
        requiredOwner(requestId, ownerId);
        return cancel(requestId);
    }

    private SearchRecord required(UUID requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new SearchNotFoundException(requestId));
    }

    private SearchRecord requiredOwner(UUID requestId, long ownerId) {
        SearchRecord record = required(requestId);
        if (record.ownerId() != ownerId) {
            throw new SearchNotFoundException(requestId);
        }
        return record;
    }

    private Map<String, Object> parameters(CreateSearchRequest request) {
        List<Map<String, Object>> sources = request.sources().stream()
                .map(source -> Map.<String, Object>of(
                        "id", source.id(), "name", source.name(), "url", source.url(),
                        "revision", source.revision(), "snapshot", source.snapshot()))
                .toList();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("userId", request.ownerId());
        parameters.put("keyword", request.keyword());
        parameters.put("mode", request.mode().name());
        parameters.put("page", request.page());
        parameters.put("sources", sources);
        return parameters;
    }

    private Map<UUID, SchedulerResult.StepResult> latestAttempts(List<SchedulerResult.StepResult> steps) {
        Map<UUID, SchedulerResult.StepResult> latest = new LinkedHashMap<>();
        steps.stream().filter(step -> "search_sources".equals(step.stepName()))
                .sorted(Comparator.comparingInt(SchedulerResult.StepResult::attempt))
                .forEach(step -> latest.put(step.executionId(), step));
        return latest;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultRows(Map<String, Object> result) {
        Object rows = result.get("results");
        if (!(rows instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList();
    }

    private SearchView basicView(SearchRecord record, List<Map<String, Object>> results,
                                 int completed, int failed, int total) {
        return new SearchView(record.id(), record.taskId(), record.status(), record.keyword(), record.mode(),
                record.page(), completed, failed, total, results, record.createdAt(), record.updatedAt());
    }

    private String aggregateStatus(String schedulerStatus, int completed, int failed) {
        if ("SUCCEEDED".equals(schedulerStatus)) {
            return failed == 0 ? "SUCCEEDED" : "PARTIAL_FAILED";
        }
        if (isTerminal(schedulerStatus) && completed > 0) {
            return "PARTIAL_FAILED";
        }
        return schedulerStatus;
    }

    private boolean isTerminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "PARTIAL_FAILED").contains(status);
    }

    private String canonicalKey(Object name) {
        return String.valueOf(name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String scopedKey(long ownerId, String key) {
        try {
            byte[] value = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "reader-search:" + ownerId + ":" + java.util.HexFormat.of().formatHex(value);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
