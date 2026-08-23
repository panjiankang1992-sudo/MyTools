package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.repository.TaskDefinitionRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 任务实例服务。
 */
@Service
public class TaskInstanceService {

    private final TaskInstanceRepository instanceRepository;
    private final TaskDefinitionRepository definitionRepository;
    private final MultiNodeTaskAggregationService multiNodeTaskAggregationService;

    /**
     * 创建任务实例服务。
     *
     * @param instanceRepository 实例仓储
     * @param definitionRepository 定义仓储
     * @param multiNodeTaskAggregationService 多节点聚合服务
     */
    public TaskInstanceService(TaskInstanceRepository instanceRepository,
                               TaskDefinitionRepository definitionRepository,
                               MultiNodeTaskAggregationService multiNodeTaskAggregationService) {
        this.instanceRepository = instanceRepository;
        this.definitionRepository = definitionRepository;
        this.multiNodeTaskAggregationService = multiNodeTaskAggregationService;
    }

    /**
     * 幂等创建任务实例。
     *
     * @param request 创建请求
     * @return 新建或已存在的任务实例
     */
    @Transactional
    public TaskInstanceView create(CreateTaskRequest request) {
        Map<String, Object> requestedLabels = normalizedRequiredLabels(request.requiredNodeLabels());
        TaskInstanceView existing = instanceRepository.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.taskName().equals(request.taskName())
                    || !existing.requiredNodeLabels().equals(requestedLabels)) {
                throw new IllegalStateException("Task idempotency key conflicts with placement constraints");
            }
            return existing;
        }
        if (request.parentTaskInstanceId() != null && instanceRepository.findById(request.parentTaskInstanceId()).isEmpty()) {
            throw new IllegalArgumentException("Parent task instance does not exist");
        }
        var definition = definitionRepository.findLatestEnabled(request.taskName())
                .orElseThrow(() -> new IllegalArgumentException("Enabled task definition does not exist"));
        try {
            return instanceRepository.insert(request, definition);
        } catch (DuplicateKeyException exception) {
            return instanceRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow(() -> exception);
        }
    }

    /**
     * 查询任务实例。
     *
     * @param id 实例标识
     * @return 任务实例
     */
    public TaskInstanceView get(UUID id) {
        return instanceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task instance does not exist"));
    }

    /**
     * 请求取消任务实例。
     *
     * @param id 实例标识
     * @return 更新后的任务实例
     */
    @Transactional
    public TaskInstanceView cancel(UUID id) {
        for (int attempt = 0; attempt < 3; attempt++) {
            TaskInstanceView current = get(id);
            if (isTerminal(current.status()) || current.status() == TaskStatus.CANCELLING) {
                return current;
            }
            if (instanceRepository.updateStatus(id, current.status(), TaskStatus.CANCELLING, Instant.now())) {
                instanceRepository.cancelQueuedTargets(id);
                multiNodeTaskAggregationService.aggregate(id, Instant.now());
                if (get(id).status() == TaskStatus.CANCELLING
                        && instanceRepository.countRunningExecutions(id) == 0) {
                    instanceRepository.updateStatus(id, TaskStatus.CANCELLING, TaskStatus.CANCELLED, Instant.now());
                }
                return get(id);
            }
        }
        throw new IllegalStateException("Task instance state changed concurrently");
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.CANCELLED || status == TaskStatus.SUCCEEDED
                || status == TaskStatus.FAILED || status == TaskStatus.TIMED_OUT;
    }

    private Map<String, Object> normalizedRequiredLabels(Map<String, Object> labels) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }
        if (labels.size() > 16) {
            throw new IllegalArgumentException("Task node affinity has too many labels");
        }
        labels.forEach((key, value) -> {
            if (key == null || !key.matches("^[A-Za-z][A-Za-z0-9_.-]{0,127}$")) {
                throw new IllegalArgumentException("Task node affinity label key is invalid");
            }
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)
                    || value instanceof String text && text.length() > 256) {
                throw new IllegalArgumentException("Task node affinity label value is invalid");
            }
        });
        return Map.copyOf(labels);
    }
}
