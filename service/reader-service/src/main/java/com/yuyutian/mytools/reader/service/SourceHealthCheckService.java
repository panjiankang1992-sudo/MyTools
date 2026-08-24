package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateHealthCheckRequest;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.HealthCheckRecord;
import com.yuyutian.mytools.reader.model.HealthCheckView;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.repository.HealthCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 书源健康检查任务编排与结果汇总服务。
 */
@Service
public class SourceHealthCheckService {

    private static final int MAX_SOURCES = 500;
    private final HealthCheckRepository repository;
    private final TaskSchedulerClient schedulerClient;

    /**
     * 创建书源健康检查服务。
     *
     * @param repository 健康检查仓储
     * @param schedulerClient 调度客户端
     */
    public SourceHealthCheckService(HealthCheckRepository repository, TaskSchedulerClient schedulerClient) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 幂等创建全部启用书源的健康检查。
     *
     * @param request 创建请求
     * @return 检查视图
     */
    @Transactional
    public HealthCheckView create(CreateHealthCheckRequest request) {
        HealthCheckRecord record = repository.findByIdempotencyKey(request.ownerId(), request.idempotencyKey())
                .orElseGet(() -> createRecord(request));
        if (!record.keyword().equals(request.keyword())) {
            throw new IllegalArgumentException("source health check idempotency conflict");
        }
        if (record.taskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_source_health_check",
                    "reader_source_health_check:" + record.id() + ":v1", "READER_SOURCE_HEALTH",
                    record.id(), 20, record.parameters());
            repository.bindTask(record.id(), taskId);
            record = required(record.id());
        }
        return view(record);
    }

    /**
     * 查询并汇总最新分片检查结果。
     *
     * @param requestId 请求标识
     * @return 检查视图
     */
    @Transactional
    public HealthCheckView get(UUID requestId) {
        HealthCheckRecord record = required(requestId);
        if (record.taskId() == null) {
            return view(record);
        }
        SchedulerResult schedulerResult = schedulerClient.getResults(record.taskId());
        Map<UUID, SchedulerResult.StepResult> latest = new LinkedHashMap<>();
        schedulerResult.steps().stream().filter(step -> "check_sources".equals(step.stepName()))
                .sorted(Comparator.comparingInt(SchedulerResult.StepResult::attempt))
                .forEach(step -> latest.put(step.executionId(), step));
        Map<UUID, Map<String, Object>> results = new LinkedHashMap<>();
        for (SchedulerResult.StepResult step : latest.values()) {
            if (!"SUCCEEDED".equals(step.status())) {
                continue;
            }
            for (Map<String, Object> result : resultRows(step.result())) {
                UUID sourceId = UUID.fromString(String.valueOf(result.get("sourceId")));
                results.put(sourceId, result);
            }
        }
        int healthy = 0;
        for (Map<String, Object> result : results.values()) {
            repository.saveResult(requestId, result);
            if ("HEALTHY".equals(result.get("status"))) {
                healthy++;
            }
        }
        String status = terminalFailure(schedulerResult.status()) && !results.isEmpty()
                ? "PARTIAL_FAILED" : schedulerResult.status();
        repository.updateSummary(requestId, status, results.size(), healthy, results.size() - healthy);
        return view(required(requestId));
    }

    /**
     * 按所有者查询书源健康检查。
     *
     * @param requestId 请求标识
     * @param ownerId 所有者标识
     * @return 检查视图
     */
    public HealthCheckView get(UUID requestId, long ownerId) {
        requiredOwner(requestId, ownerId);
        return get(requestId);
    }

    /**
     * 取消健康检查任务。
     *
     * @param requestId 请求标识
     * @return 检查视图
     */
    @Transactional
    public HealthCheckView cancel(UUID requestId) {
        HealthCheckRecord record = required(requestId);
        if (record.taskId() != null && !terminal(record.status())) {
            schedulerClient.cancel(record.taskId());
        }
        return get(requestId);
    }

    /**
     * 按所有者取消书源健康检查。
     *
     * @param requestId 请求标识
     * @param ownerId 所有者标识
     * @return 检查视图
     */
    public HealthCheckView cancel(UUID requestId, long ownerId) {
        requiredOwner(requestId, ownerId);
        return cancel(requestId);
    }

    private HealthCheckRecord createRecord(CreateHealthCheckRequest request) {
        List<Map<String, Object>> sources = repository.findEnabledSources(request.ownerId());
        if (sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException(ErrorCode.HEALTH_SOURCE_LIMIT.code());
        }
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("requestId", id.toString());
        parameters.put("ownerId", request.ownerId());
        parameters.put("keyword", request.keyword());
        parameters.put("sources", sources);
        HealthCheckRecord record = new HealthCheckRecord(id, request.ownerId(), request.idempotencyKey(),
                request.keyword(), "ACCEPTED", null, parameters, 0, 0, 0, now, now);
        repository.insert(record);
        return record;
    }

    private HealthCheckRecord required(UUID requestId) {
        return repository.findById(requestId).orElseThrow(() -> new HealthCheckNotFoundException(requestId));
    }

    private HealthCheckRecord requiredOwner(UUID requestId, long ownerId) {
        HealthCheckRecord record = required(requestId);
        if (record.ownerId() != ownerId) {
            throw new HealthCheckNotFoundException(requestId);
        }
        return record;
    }

    private HealthCheckView view(HealthCheckRecord record) {
        return new HealthCheckView(record.id(), record.taskId(), record.status(), record.keyword(), record.checked(),
                record.healthy(), record.unhealthy(), record.createdAt(), record.updatedAt());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultRows(Map<String, Object> result) {
        Object rows = result.get("results");
        if (!(rows instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList();
    }

    private boolean terminalFailure(String status) {
        return "FAILED".equals(status) || "TIMED_OUT".equals(status) || "CANCELLED".equals(status);
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || terminalFailure(status) || "PARTIAL_FAILED".equals(status);
    }
}
