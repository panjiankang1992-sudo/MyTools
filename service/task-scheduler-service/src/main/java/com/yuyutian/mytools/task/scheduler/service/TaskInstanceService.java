package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.repository.TaskDefinitionRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
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
    private final TaskCancellationPropagationService cancellationPropagationService;
    private final TaskSchemaValidationService schemaValidationService;
    private final TaskEventService taskEventService;

    /**
     * 创建任务实例服务。
     *
     * @param instanceRepository 实例仓储
     * @param definitionRepository 定义仓储
     * @param multiNodeTaskAggregationService 多节点聚合服务
     * @param cancellationPropagationService 取消传播服务
     * @param schemaValidationService 模式校验服务
     * @param taskEventService 任务事件服务
     */
    public TaskInstanceService(TaskInstanceRepository instanceRepository,
                               TaskDefinitionRepository definitionRepository,
                               MultiNodeTaskAggregationService multiNodeTaskAggregationService,
                               TaskCancellationPropagationService cancellationPropagationService,
                               TaskSchemaValidationService schemaValidationService,
                               TaskEventService taskEventService) {
        this.instanceRepository = instanceRepository;
        this.definitionRepository = definitionRepository;
        this.multiNodeTaskAggregationService = multiNodeTaskAggregationService;
        this.cancellationPropagationService = cancellationPropagationService;
        this.schemaValidationService = schemaValidationService;
        this.taskEventService = taskEventService;
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
        var definition = definitionRepository.findLatestEnabled(request.taskName())
                .orElseThrow(() -> new IllegalArgumentException("Enabled task definition does not exist"));
        schemaValidationService.validateParameters(definition.parameterSchema(), contractParameters(request));
        TaskInstanceView existing = instanceRepository.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.taskName().equals(request.taskName())
                    || !existing.requiredNodeLabels().equals(requestedLabels)
                    || !existing.parameters().equals(request.parameters())
                    || !java.util.Objects.equals(existing.businessType(), request.businessType())
                    || !java.util.Objects.equals(existing.businessId(), request.businessId())
                    || !java.util.Objects.equals(existing.parentTaskInstanceId(), request.parentTaskInstanceId())
                    || existing.priority() != request.priority()) {
                throw new SchedulerException(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT,
                        "Task idempotency key conflicts with the stored request");
            }
            return existing;
        }
        if (request.parentTaskInstanceId() != null) {
            cancellationPropagationService.validateChildCreation(request.parentTaskInstanceId());
        }
        try {
            TaskInstanceView created = instanceRepository.insert(request, definition);
            taskEventService.appendTransition(created.id(), null, TaskStatus.QUEUED.name(), "create",
                    "TASK_CREATED", 0, created.createdAt());
            return created;
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
        cancellationPropagationService.requestCancellation(id, "cancel");
        cancellationPropagationService.enqueueActiveChildren(id, id, 2);
        // 同步推进一个有界批次，让小任务树无需等待后台扫描，大任务树仍不会形成无界事务递归。
        cancellationPropagationService.processPendingBatch();
        multiNodeTaskAggregationService.aggregate(id, Instant.now());
        cancellationPropagationService.finalizeCancellationChain(id);
        return get(id);
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

    private Map<String, Object> contractParameters(CreateTaskRequest request) {
        if (!"SCHEDULED_TASK".equals(request.businessType())) {
            return request.parameters();
        }
        // Cron 调度元数据属于平台保留上下文，不参与任务定义的业务参数 Schema 校验。
        java.util.LinkedHashMap<String, Object> parameters = new java.util.LinkedHashMap<>(request.parameters());
        parameters.remove("scheduledAt");
        parameters.remove("definitionVersion");
        return Map.copyOf(parameters);
    }
}
