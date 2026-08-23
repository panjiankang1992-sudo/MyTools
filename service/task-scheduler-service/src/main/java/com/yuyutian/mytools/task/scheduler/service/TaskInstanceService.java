package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务实例最小实现，阶段一使用内存存储验证契约，后续替换为持久化仓储。
 */
@Service
public class TaskInstanceService {

    private final Map<UUID, TaskInstanceView> instances = new ConcurrentHashMap<>();
    private final Map<String, UUID> idempotencyIndex = new ConcurrentHashMap<>();

    /**
     * 幂等创建任务实例。
     *
     * @param request 创建请求
     * @return 新建或已存在的任务实例
     */
    public synchronized TaskInstanceView create(CreateTaskRequest request) {
        UUID existingId = idempotencyIndex.get(request.idempotencyKey());
        if (existingId != null) {
            return instances.get(existingId);
        }
        if (request.parentTaskInstanceId() != null && !instances.containsKey(request.parentTaskInstanceId())) {
            throw new IllegalArgumentException("Parent task instance does not exist");
        }
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        TaskInstanceView view = new TaskInstanceView(
                id,
                request.taskName(),
                request.idempotencyKey(),
                request.parentTaskInstanceId(),
                request.businessType(),
                request.businessId(),
                request.priority(),
                Map.copyOf(request.parameters()),
                TaskStatus.QUEUED,
                now,
                now
        );
        instances.put(id, view);
        idempotencyIndex.put(request.idempotencyKey(), id);
        return view;
    }

    /**
     * 查询任务实例。
     *
     * @param id 实例标识
     * @return 任务实例
     */
    public TaskInstanceView get(UUID id) {
        TaskInstanceView view = instances.get(id);
        if (view == null) {
            throw new IllegalArgumentException("Task instance does not exist");
        }
        return view;
    }

    /**
     * 请求取消任务实例。
     *
     * @param id 实例标识
     * @return 更新后的任务实例
     */
    public synchronized TaskInstanceView cancel(UUID id) {
        TaskInstanceView current = get(id);
        if (isTerminal(current.status())) {
            return current;
        }
        TaskInstanceView updated = new TaskInstanceView(
                current.id(), current.taskName(), current.idempotencyKey(), current.parentTaskInstanceId(),
                current.businessType(), current.businessId(), current.priority(), current.parameters(),
                TaskStatus.CANCELLING, current.createdAt(), Instant.now()
        );
        instances.put(id, updated);
        return updated;
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.CANCELLED || status == TaskStatus.SUCCEEDED
                || status == TaskStatus.FAILED || status == TaskStatus.TIMED_OUT;
    }
}
