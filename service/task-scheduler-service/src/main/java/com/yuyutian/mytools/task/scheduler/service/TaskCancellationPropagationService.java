package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 使用持久化队列有界传播父子任务取消请求。
 */
@Service
public class TaskCancellationPropagationService {

    static final int MAX_TASK_DEPTH = 8;
    static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final TaskInstanceRepository instanceRepository;
    private final TaskEventService taskEventService;
    private final ChildTaskAggregationService childTaskAggregationService;
    private final TaskExecutionAuthorizationService executionAuthorizationService;

    /**
     * 创建任务取消传播服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param instanceRepository 任务实例仓储
     * @param taskEventService 任务事件服务
     * @param childTaskAggregationService 父子任务聚合服务
     */
    public TaskCancellationPropagationService(JdbcTemplate jdbcTemplate,
                                              TaskInstanceRepository instanceRepository,
                                              TaskEventService taskEventService,
                                              ChildTaskAggregationService childTaskAggregationService,
                                              TaskExecutionAuthorizationService executionAuthorizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.instanceRepository = instanceRepository;
        this.taskEventService = taskEventService;
        this.childTaskAggregationService = childTaskAggregationService;
        this.executionAuthorizationService = executionAuthorizationService;
    }

    /**
     * 锁定父任务并验证新子任务不会突破树深度和直接子任务限制。
     *
     * @param parentTaskId 父任务标识
     */
    @Transactional
    public void validateChildCreation(UUID parentTaskId) {
        UUID current = parentTaskId;
        int depth = 0;
        while (current != null) {
            List<String> parents = jdbcTemplate.query("""
                    SELECT parent_task_instance_id FROM task_instance WHERE id = ? FOR UPDATE
                    """, (resultSet, rowNumber) -> resultSet.getString(1), current.toString());
            if (parents.isEmpty()) {
                throw new IllegalArgumentException("Parent task instance does not exist");
            }
            depth++;
            if (depth >= MAX_TASK_DEPTH) {
                throw new IllegalStateException("Task tree depth limit was reached");
            }
            String parent = parents.getFirst();
            current = parent == null ? null : UUID.fromString(parent);
        }
        Integer childCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE parent_task_instance_id = ?",
                Integer.class, parentTaskId.toString());
        if (childCount != null && childCount >= 1000) {
            throw new IllegalStateException("Direct child task limit was reached");
        }
    }

    /**
     * 将活跃直接子任务加入持久化取消队列。
     *
     * @param rootTaskId 根取消任务标识
     * @param parentTaskId 当前父任务标识
     * @param depth 子任务深度
     */
    @Transactional
    public void enqueueActiveChildren(UUID rootTaskId, UUID parentTaskId, int depth) {
        List<UUID> childIds = instanceRepository.findActiveChildIds(parentTaskId);
        if (childIds.isEmpty()) {
            return;
        }
        if (depth > MAX_TASK_DEPTH) {
            throw new IllegalStateException("Task tree depth limit was exceeded");
        }
        Instant now = Instant.now();
        for (UUID childId : childIds) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO task_cancellation_queue
                        (root_task_instance_id, task_instance_id, depth, status, created_at)
                        VALUES (?, ?, ?, 'PENDING', ?)
                        """, rootTaskId.toString(), childId.toString(), depth, Timestamp.from(now));
            } catch (DuplicateKeyException ignored) {
                // 相同任务的取消传播已经进入持久队列。
            }
        }
    }

    /**
     * 定时处理一批持久化取消传播记录。
     *
     * @return 本批处理数量
     */
    @Scheduled(fixedDelayString = "${task.scheduler.cancellation-scan-delay-ms:1000}")
    @Transactional
    public int processPendingBatch() {
        List<CancellationEntry> entries = jdbcTemplate.query("""
                SELECT id, root_task_instance_id, task_instance_id, depth
                FROM task_cancellation_queue
                WHERE status = 'PENDING'
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new CancellationEntry(
                resultSet.getLong("id"), UUID.fromString(resultSet.getString("root_task_instance_id")),
                UUID.fromString(resultSet.getString("task_instance_id")), resultSet.getInt("depth")), BATCH_SIZE);
        for (CancellationEntry entry : entries) {
            requestCancellation(entry.taskId(), "parent-cancel:" + entry.rootTaskId());
            enqueueActiveChildren(entry.rootTaskId(), entry.taskId(), entry.depth() + 1);
            jdbcTemplate.update("""
                    UPDATE task_cancellation_queue SET status = 'PROCESSED', processed_at = ?
                    WHERE id = ? AND status = 'PENDING'
                    """, Timestamp.from(Instant.now()), entry.id());
            finalizeCancellationChain(entry.taskId());
        }
        return entries.size();
    }

    /**
     * 将单个任务幂等切换为取消中，并处理无运行执行的叶子任务。
     *
     * @param taskId 任务标识
     * @param sourceId 取消来源
     */
    @Transactional
    public void requestCancellation(UUID taskId, String sourceId) {
        var current = instanceRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task instance does not exist"));
        if (isTerminal(current.status()) || current.status() == TaskStatus.CANCELLING) {
            return;
        }
        Instant now = Instant.now();
        if (instanceRepository.updateStatus(taskId, current.status(), TaskStatus.CANCELLING, now)) {
            // 正文执行授权与取消状态一起提交，旧 generation 不享有在途重叠期。
            executionAuthorizationService.revokeTask(taskId);
            taskEventService.appendTransition(taskId, current.status().name(), TaskStatus.CANCELLING.name(),
                    sourceId, "CANCELLATION_REQUESTED", null, now);
            instanceRepository.cancelQueuedTargets(taskId);
        }
    }

    /**
     * 在执行或子任务进入终态后，尝试完成当前任务及其取消中的祖先。
     *
     * @param taskId 当前任务标识
     */
    @Transactional
    public void finalizeCancellationChain(UUID taskId) {
        UUID currentId = taskId;
        for (int depth = 0; currentId != null && depth < MAX_TASK_DEPTH; depth++) {
            var current = instanceRepository.findById(currentId).orElse(null);
            if (current == null) {
                return;
            }
            if (current.status() == TaskStatus.CANCELLING
                    && instanceRepository.countRunningExecutions(currentId) == 0
                    && instanceRepository.countActiveChildren(currentId) == 0
                    && countPendingQueueEntries(currentId) == 0) {
                Instant now = Instant.now();
                if (instanceRepository.updateStatus(currentId, TaskStatus.CANCELLING, TaskStatus.CANCELLED, now)) {
                    taskEventService.appendTransition(currentId, TaskStatus.CANCELLING.name(),
                            TaskStatus.CANCELLED.name(), "cancel-propagation", "CANCELLATION_COMPLETED", 0, now);
                    childTaskAggregationService.aggregateParentChain(currentId, now);
                }
            }
            currentId = current.parentTaskInstanceId();
        }
    }

    private int countPendingQueueEntries(UUID taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_cancellation_queue
                WHERE task_instance_id = ? AND status = 'PENDING'
                """, Integer.class, taskId.toString());
        return count == null ? 0 : count;
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.CANCELLED || status == TaskStatus.SUCCEEDED
                || status == TaskStatus.FAILED || status == TaskStatus.TIMED_OUT;
    }

    private record CancellationEntry(long id, UUID rootTaskId, UUID taskId, int depth) {
    }
}
