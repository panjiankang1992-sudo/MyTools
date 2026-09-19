package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.NodeHealthProperties;
import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.ClaimedStepView;
import com.yuyutian.mytools.task.scheduler.model.ClaimedTaskView;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.CompensationStatus;
import com.yuyutian.mytools.task.scheduler.model.ExecutionReportView;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatRequest;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatView;
import com.yuyutian.mytools.task.scheduler.model.ReportStepExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskStepRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HexFormat;

/**
 * 任务分发、租约和执行结果服务。
 */
@Service
public class TaskDispatchService {

    private static final long LOG_SEGMENT_MAX_BYTES = 8L * 1024 * 1024;
    private static final int LOG_SEGMENT_MAX_COUNT = 1024;
    private static final int PLACEMENT_SCAN_BATCH_SIZE = 32;

    private final JdbcTemplate jdbcTemplate;
    private final TaskInstanceRepository instanceRepository;
    private final TaskStepRepository stepRepository;
    private final JsonColumnMapper jsonColumnMapper;
    private final MultiNodeTaskAggregationService multiNodeTaskAggregationService;
    private final TaskSchemaValidationService schemaValidationService;
    private final TaskEventService taskEventService;
    private final NodeHealthProperties nodeHealthProperties;
    private final ChildTaskAggregationService childTaskAggregationService;
    private final TaskCancellationPropagationService cancellationPropagationService;
    private final TaskExecutionAuthorizationService executionAuthorizationService;

    /**
     * 创建任务分发服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param instanceRepository 任务实例仓储
     * @param stepRepository 任务步骤仓储
     * @param jsonColumnMapper JSON 转换器
     * @param multiNodeTaskAggregationService 多节点聚合服务
     * @param schemaValidationService 模式校验服务
     * @param taskEventService 任务事件服务
     * @param nodeHealthProperties 节点健康配置
     * @param childTaskAggregationService 父子任务聚合服务
     * @param cancellationPropagationService 取消传播服务
     */
    public TaskDispatchService(JdbcTemplate jdbcTemplate, TaskInstanceRepository instanceRepository,
                               TaskStepRepository stepRepository, JsonColumnMapper jsonColumnMapper,
                               MultiNodeTaskAggregationService multiNodeTaskAggregationService,
                               TaskSchemaValidationService schemaValidationService,
                               TaskEventService taskEventService,
                               NodeHealthProperties nodeHealthProperties,
                               ChildTaskAggregationService childTaskAggregationService,
                               TaskCancellationPropagationService cancellationPropagationService,
                               TaskExecutionAuthorizationService executionAuthorizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.instanceRepository = instanceRepository;
        this.stepRepository = stepRepository;
        this.jsonColumnMapper = jsonColumnMapper;
        this.multiNodeTaskAggregationService = multiNodeTaskAggregationService;
        this.schemaValidationService = schemaValidationService;
        this.taskEventService = taskEventService;
        this.nodeHealthProperties = nodeHealthProperties;
        this.childTaskAggregationService = childTaskAggregationService;
        this.cancellationPropagationService = cancellationPropagationService;
        this.executionAuthorizationService = executionAuthorizationService;
    }

    /**
     * 为执行节点领取一个符合集群约束的任务。
     *
     * @param request 领取请求
     * @return 可选已领取任务
     */
    @Transactional
    public Optional<ClaimedTaskView> claim(ClaimTaskRequest request) {
        boolean releaseDraining = lockAndValidateNodeInstance(request.nodeId(), request.instanceId());
        Optional<ClaimedTaskView> replayedClaim = findClaimByRequest(request);
        if (replayedClaim.isPresent()) {
            return replayedClaim;
        }
        Map<String, Object> nodeLabels = nodeLabels(request.nodeId());
        Optional<ClaimedTaskView> existingTarget = claimExecutionTarget(request, releaseDraining);
        if (existingTarget.isPresent()) {
            return existingTarget;
        }
        expandNextMultiNodeTask(request, nodeLabels, releaseDraining);
        Optional<ClaimedTaskView> expandedTarget = claimExecutionTarget(request, releaseDraining);
        if (expandedTarget.isPresent()) {
            return expandedTarget;
        }
        Instant selectionTime = Instant.now();
        PlacementCursor cursor = null;
        // 按严格总序扫描所有分页，避免高优先级但标签不匹配的任务遮挡后续可领取任务。
        while (true) {
            List<PlacementCandidate> candidates = findSingleNodePlacementCandidates(
                    request, selectionTime, cursor, releaseDraining);
            if (candidates.isEmpty()) {
                break;
            }
            for (PlacementCandidate candidate : candidates) {
                if (!matchesRequiredLabels(candidate.requiredLabels(), nodeLabels)) {
                    continue;
                }
                UUID taskId = candidate.taskId();
                CapacityDecision capacity = capacityDecision(taskId, request.nodeId());
                if (capacity == CapacityDecision.RESERVE_FOR_ORCHESTRATOR) {
                    // 高优先级编排任务只差瞬时容量时立即停止本次领取，禁止同范围低需求任务回填保留槽。
                    throw new ClaimCapacityReservedException();
                }
                if (capacity != CapacityDecision.AVAILABLE) {
                    continue;
                }
                Instant now = Instant.now();
                int claimed = jdbcTemplate.update("""
                        UPDATE task_instance
                        SET status = 'RUNNING', dispatch_attempts = dispatch_attempts + 1,
                            started_at = COALESCE(started_at, ?), available_at = NULL, updated_at = ?
                        WHERE id = ? AND status = 'QUEUED'
                          AND (started_at IS NOT NULL OR (
                              dispatch_deadline_at IS NOT NULL AND dispatch_deadline_at > ?
                          ))
                        """, Timestamp.from(now), Timestamp.from(now), taskId.toString(), Timestamp.from(now));
                if (claimed == 1) {
                    ClaimedTaskView execution = createExecution(request, taskId, null, null);
                    taskEventService.appendTransition(taskId, "QUEUED", "RUNNING",
                            execution.executionId().toString(), "EXECUTION_CLAIMED", 0, now);
                    return Optional.of(execution);
                }
            }
            cursor = PlacementCursor.after(candidates.getLast());
            if (candidates.size() < PLACEMENT_SCAN_BATCH_SIZE) {
                break;
            }
        }
        return Optional.empty();
    }

    private List<PlacementCandidate> findSingleNodePlacementCandidates(ClaimTaskRequest request,
                                                                        Instant selectionTime,
                                                                        PlacementCursor cursor,
                                                                        boolean releaseDraining) {
        TaskScope taskScope = taskScope(request, "ti", releaseDraining);
        String cursorCondition = cursor == null ? "" : """
                  AND (
                      ti.priority < ?
                      OR (ti.priority = ? AND ti.created_at > ?)
                      OR (ti.priority = ? AND ti.created_at = ? AND ti.id > ?)
                  )
                """;
        String sql = """
                SELECT ti.id, ti.parent_task_instance_id, ti.required_node_labels_json,
                       ti.priority, ti.created_at
                FROM task_instance ti
                JOIN task_definition td ON td.id = ti.task_definition_id
                JOIN execution_cluster ec ON ec.id = td.cluster_id AND ec.enabled = TRUE
                JOIN cluster_node cn ON cn.cluster_id = ec.id AND cn.enabled = TRUE
                WHERE ti.status = 'QUEUED' AND cn.node_id = ?
                """ + taskScope.condition() + """
                  AND (ti.available_at IS NULL OR ti.available_at <= CURRENT_TIMESTAMP)
                  AND (ti.started_at IS NOT NULL OR (
                      ti.dispatch_deadline_at IS NOT NULL
                      AND ti.dispatch_deadline_at > ?
                  ))
                  AND ti.created_at <= ?
                  AND td.execution_mode = 'SINGLE_NODE'
                  AND (
                      SELECT COUNT(*) FROM task_instance running_definition
                      WHERE running_definition.task_definition_id = td.id
                        AND running_definition.status = 'RUNNING'
                  ) < td.max_concurrency
                  AND (
                      SELECT COUNT(*) FROM task_execution running_execution
                      JOIN task_instance running_instance
                        ON running_instance.id = running_execution.task_instance_id
                      JOIN task_definition running_task_definition
                        ON running_task_definition.id = running_instance.task_definition_id
                      WHERE running_task_definition.cluster_id = ec.id
                        AND running_execution.status = 'RUNNING'
                  ) < ec.max_concurrent_tasks
                  AND EXISTS (
                      SELECT 1 FROM task_step_definition ts
                      WHERE ts.task_definition_id = td.id AND ts.enabled = TRUE AND ts.step_kind = 'NORMAL'
                )
                """ + cursorCondition + """
                ORDER BY ti.priority DESC, ti.created_at, ti.id
                LIMIT ?
                """;
        List<Object> parameters = new ArrayList<>();
        parameters.add(request.nodeId().toString());
        parameters.addAll(taskScope.parameters());
        parameters.add(Timestamp.from(selectionTime));
        parameters.add(Timestamp.from(selectionTime));
        if (cursor != null) {
            parameters.add(cursor.priority());
            parameters.add(cursor.priority());
            parameters.add(Timestamp.from(cursor.createdAt()));
            parameters.add(cursor.priority());
            parameters.add(Timestamp.from(cursor.createdAt()));
            parameters.add(cursor.taskId().toString());
        }
        parameters.add(PLACEMENT_SCAN_BATCH_SIZE);
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new PlacementCandidate(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("parent_task_instance_id") == null,
                jsonColumnMapper.read(resultSet.getString("required_node_labels_json")),
                resultSet.getInt("priority"), resultSet.getTimestamp("created_at").toInstant()),
                parameters.toArray());
    }

    private Optional<ClaimedTaskView> claimExecutionTarget(ClaimTaskRequest request, boolean releaseDraining) {
        Map<String, Object> nodeLabels = nodeLabels(request.nodeId());
        TaskScope taskScope = taskScope(request, "ti", releaseDraining);
        String sql = """
                SELECT et.id, et.task_instance_id, et.target_index, et.target_count, td.execution_mode,
                       ti.required_node_labels_json
                FROM task_execution_target et
                JOIN task_instance ti ON ti.id = et.task_instance_id
                JOIN task_definition td ON td.id = ti.task_definition_id
                WHERE et.node_id = ? AND et.status = 'QUEUED' AND ti.status IN ('RUNNING', 'CANCELLING')
                """ + taskScope.condition() + """
                  AND (et.available_at IS NULL OR et.available_at <= CURRENT_TIMESTAMP)
                ORDER BY ti.priority DESC, et.created_at
                LIMIT 32
                FOR UPDATE SKIP LOCKED
                """;
        List<Object> parameters = new ArrayList<>();
        parameters.add(request.nodeId().toString());
        parameters.addAll(taskScope.parameters());
        List<ExecutionTarget> targets = jdbcTemplate.query(sql, (resultSet, rowNumber) -> new ExecutionTarget(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("task_instance_id")),
                resultSet.getInt("target_index"), resultSet.getInt("target_count"),
                resultSet.getString("execution_mode"),
                jsonColumnMapper.read(resultSet.getString("required_node_labels_json"))
        ), parameters.toArray());
        for (ExecutionTarget target : targets) {
            if (!matchesRequiredLabels(target.requiredLabels(), nodeLabels)) {
                continue;
            }
            if (!hasExecutionCapacity(target.taskInstanceId(), request.nodeId())) {
                continue;
            }
            int claimed = jdbcTemplate.update("""
                    UPDATE task_execution_target
                    SET status = 'RUNNING', dispatch_attempts = dispatch_attempts + 1,
                        available_at = NULL, updated_at = ?
                    WHERE id = ? AND status = 'QUEUED'
                    """, Timestamp.from(Instant.now()), target.id().toString());
            if (claimed == 1) {
                return Optional.of(createExecution(request, target.taskInstanceId(), target.id(), target));
            }
        }
        return Optional.empty();
    }

    private void expandNextMultiNodeTask(ClaimTaskRequest request, Map<String, Object> nodeLabels,
                                         boolean releaseDraining) {
        Instant selectionTime = Instant.now();
        PlacementCursor cursor = null;
        // 多节点任务使用相同游标策略，确保标签匹配任务不会被固定的首批候选饿死。
        while (true) {
            List<PlacementCandidate> candidates = findMultiNodePlacementCandidates(
                    request, selectionTime, cursor, releaseDraining);
            if (candidates.isEmpty()) {
                return;
            }
            for (PlacementCandidate candidate : candidates) {
                if (!matchesRequiredLabels(candidate.requiredLabels(), nodeLabels)) {
                    continue;
                }
                UUID taskId = candidate.taskId();
                if (!hasDefinitionCapacity(taskId)) {
                    continue;
                }
                Instant now = Instant.now();
                int claimed = jdbcTemplate.update("""
                        UPDATE task_instance
                        SET status = 'RUNNING', dispatch_attempts = dispatch_attempts + 1,
                            started_at = COALESCE(started_at, ?), available_at = NULL, updated_at = ?
                        WHERE id = ? AND status = 'QUEUED'
                          AND (started_at IS NOT NULL OR (
                              dispatch_deadline_at IS NOT NULL AND dispatch_deadline_at > ?
                          ))
                        """, Timestamp.from(now), Timestamp.from(now), taskId.toString(), Timestamp.from(now));
                if (claimed == 1) {
                    int expanded = createExecutionTargets(taskId);
                    if (expanded == 0) {
                        jdbcTemplate.update("UPDATE task_instance SET status = 'QUEUED', updated_at = ? WHERE id = ?",
                                Timestamp.from(Instant.now()), taskId.toString());
                    } else {
                        taskEventService.appendTransition(taskId, "QUEUED", "RUNNING", "dispatch",
                                "EXECUTION_TARGETS_CREATED", 0, now);
                    }
                    return;
                }
            }
            cursor = PlacementCursor.after(candidates.getLast());
            if (candidates.size() < PLACEMENT_SCAN_BATCH_SIZE) {
                return;
            }
        }
    }

    private List<PlacementCandidate> findMultiNodePlacementCandidates(ClaimTaskRequest request,
                                                                       Instant selectionTime,
                                                                       PlacementCursor cursor,
                                                                       boolean releaseDraining) {
        TaskScope taskScope = taskScope(request, "ti", releaseDraining);
        String cursorCondition = cursor == null ? "" : """
                  AND (
                      ti.priority < ?
                      OR (ti.priority = ? AND ti.created_at > ?)
                      OR (ti.priority = ? AND ti.created_at = ? AND ti.id > ?)
                  )
                """;
        String sql = """
                SELECT ti.id, ti.parent_task_instance_id, ti.required_node_labels_json,
                       ti.priority, ti.created_at
                FROM task_instance ti
                JOIN task_definition td ON td.id = ti.task_definition_id
                JOIN cluster_node cn ON cn.cluster_id = td.cluster_id AND cn.enabled = TRUE
                WHERE ti.status = 'QUEUED' AND cn.node_id = ?
                """ + taskScope.condition() + """
                  AND (ti.started_at IS NOT NULL OR (
                      ti.dispatch_deadline_at IS NOT NULL
                      AND ti.dispatch_deadline_at > ?
                  ))
                  AND ti.created_at <= ?
                  AND td.execution_mode IN ('MULTI_NODE_BROADCAST', 'MULTI_NODE_SHARD')
                  AND EXISTS (
                      SELECT 1 FROM task_step_definition ts
                      WHERE ts.task_definition_id = td.id AND ts.enabled = TRUE AND ts.step_kind = 'NORMAL'
                )
                """ + cursorCondition + """
                ORDER BY ti.priority DESC, ti.created_at, ti.id
                LIMIT ?
                """;
        List<Object> parameters = new ArrayList<>();
        parameters.add(request.nodeId().toString());
        parameters.addAll(taskScope.parameters());
        parameters.add(Timestamp.from(selectionTime));
        parameters.add(Timestamp.from(selectionTime));
        if (cursor != null) {
            parameters.add(cursor.priority());
            parameters.add(cursor.priority());
            parameters.add(Timestamp.from(cursor.createdAt()));
            parameters.add(cursor.priority());
            parameters.add(Timestamp.from(cursor.createdAt()));
            parameters.add(cursor.taskId().toString());
        }
        parameters.add(PLACEMENT_SCAN_BATCH_SIZE);
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new PlacementCandidate(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("parent_task_instance_id") == null,
                jsonColumnMapper.read(resultSet.getString("required_node_labels_json")),
                resultSet.getInt("priority"), resultSet.getTimestamp("created_at").toInstant()),
                parameters.toArray());
    }

    private int createExecutionTargets(UUID taskId) {
        List<EligibleNode> nodes = jdbcTemplate.query("""
                SELECT en.id, en.labels_json, ti.required_node_labels_json
                FROM task_instance ti
                JOIN task_definition td ON td.id = ti.task_definition_id
                JOIN cluster_node cn ON cn.cluster_id = td.cluster_id AND cn.enabled = TRUE
                JOIN executor_node en ON en.id = cn.node_id
                WHERE ti.id = ? AND en.enabled = TRUE AND en.status IN ('ONLINE', 'BUSY')
                  AND en.last_heartbeat_at >= ?
                ORDER BY cn.priority DESC, cn.weight DESC, en.id
                """, (resultSet, rowNumber) -> new EligibleNode(
                UUID.fromString(resultSet.getString("id")),
                jsonColumnMapper.read(resultSet.getString("labels_json")),
                jsonColumnMapper.read(resultSet.getString("required_node_labels_json"))), taskId.toString(),
                Timestamp.from(Instant.now().minusSeconds(nodeHealthProperties.offlineAfterSeconds())));
        List<UUID> nodeIds = nodes.stream()
                .filter(node -> matchesRequiredLabels(node.requiredLabels(), node.labels()))
                .map(EligibleNode::nodeId)
                .toList();
        Instant now = Instant.now();
        for (int index = 0; index < nodeIds.size(); index++) {
            jdbcTemplate.update("""
                    INSERT INTO task_execution_target
                    (id, task_instance_id, node_id, target_index, target_count, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                    """, UUID.randomUUID().toString(), taskId.toString(), nodeIds.get(index).toString(), index,
                    nodeIds.size(), Timestamp.from(now), Timestamp.from(now));
        }
        return nodeIds.size();
    }

    private CapacityDecision capacityDecision(UUID taskId, UUID nodeId) {
        CapacityDefinition definition = lockCapacityDefinition(taskId);
        if (definition == null || !hasDefinitionCapacity(definition)) {
            return CapacityDecision.UNAVAILABLE;
        }
        TaskCapacityRequirement requirement = taskCapacityRequirement(taskId, definition.definitionId());
        ExecutionCapacity capacity = executionCapacity(definition, nodeId, requirement.minimumSlots());
        if (requirement.rootTask()) {
            OrchestrationReservation reservation = orchestrationReservation(definition.clusterId(), nodeId);
            if (capacity.clusterRemaining() - 1 < reservation.clusterSlots()
                    || capacity.nodeRemaining() - 1 < reservation.nodeSlots()) {
                return CapacityDecision.UNAVAILABLE;
            }
        }
        if (capacity.available()) {
            return CapacityDecision.AVAILABLE;
        }
        if (requirement.minimumSlots() > 1 && capacity.theoreticallyFits()) {
            return CapacityDecision.RESERVE_FOR_ORCHESTRATOR;
        }
        return CapacityDecision.UNAVAILABLE;
    }

    private boolean hasDefinitionCapacity(UUID taskId) {
        CapacityDefinition definition = lockCapacityDefinition(taskId);
        return definition != null && hasDefinitionCapacity(definition);
    }

    private CapacityDefinition lockCapacityDefinition(UUID taskId) {
        // 锁定定义和集群容量行，确保多副本并发领取不会突破共享上限。
        return jdbcTemplate.queryForObject("""
                SELECT td.id AS definition_id, td.cluster_id, td.max_concurrency,
                       ec.max_concurrent_tasks AS cluster_max_concurrency
                FROM task_instance ti
                JOIN task_definition td ON td.id = ti.task_definition_id
                JOIN execution_cluster ec ON ec.id = td.cluster_id
                WHERE ti.id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new CapacityDefinition(
                UUID.fromString(resultSet.getString("definition_id")),
                UUID.fromString(resultSet.getString("cluster_id")),
                resultSet.getInt("max_concurrency"),
                resultSet.getInt("cluster_max_concurrency")
        ), taskId.toString());
    }

    private boolean hasDefinitionCapacity(CapacityDefinition definition) {
        int definitionRunning = count("""
                SELECT COUNT(*) FROM task_instance
                WHERE task_definition_id = ? AND status = 'RUNNING'
                """, definition.definitionId().toString());
        return definitionRunning < definition.maxConcurrency();
    }

    private boolean hasExecutionCapacity(UUID taskId, UUID nodeId) {
        CapacityDefinition definition = lockCapacityDefinition(taskId);
        return definition != null && executionCapacity(
                definition, nodeId, taskCapacityRequirement(taskId, definition.definitionId()).minimumSlots())
                .available();
    }

    private ExecutionCapacity executionCapacity(CapacityDefinition definition, UUID nodeId,
                                                int minimumExecutionSlots) {
        int clusterRunning = count("""
                SELECT COUNT(*) FROM task_execution te
                JOIN task_instance ti ON ti.id = te.task_instance_id
                JOIN task_definition td ON td.id = ti.task_definition_id
                WHERE td.cluster_id = ? AND te.status = 'RUNNING'
                """, definition.clusterId().toString());
        Integer nodeLimit = jdbcTemplate.queryForObject(
                "SELECT max_concurrent_tasks FROM executor_node WHERE id = ? FOR UPDATE",
                Integer.class, nodeId.toString());
        int nodeRunning = count(
                "SELECT COUNT(*) FROM task_execution WHERE node_id = ? AND status = 'RUNNING'",
                nodeId.toString());
        int normalizedNodeLimit = nodeLimit == null ? 0 : nodeLimit;
        boolean available = definition.clusterMaxConcurrency() - clusterRunning >= minimumExecutionSlots
                && normalizedNodeLimit - nodeRunning >= minimumExecutionSlots;
        boolean theoreticallyFits = definition.clusterMaxConcurrency() >= minimumExecutionSlots
                && normalizedNodeLimit >= minimumExecutionSlots;
        return new ExecutionCapacity(available, theoreticallyFits,
                definition.clusterMaxConcurrency() - clusterRunning,
                normalizedNodeLimit - nodeRunning);
    }

    private TaskCapacityRequirement taskCapacityRequirement(UUID taskId, UUID definitionId) {
        var task = instanceRepository.findById(taskId).orElseThrow();
        List<String> scriptPackages = stepRepository.list(definitionId).stream()
                .filter(step -> step.enabled())
                .map(step -> step.scriptPackage())
                .toList();
        // 一个正在等待的编排任务必须在领取前留出一条完整后继链。
        int minimumSlots = 1 + TaskOrchestrationCatalog.maximumDescendantDepth(
                task.taskName(), scriptPackages);
        return new TaskCapacityRequirement(minimumSlots, task.parentTaskInstanceId() == null);
    }

    private OrchestrationReservation orchestrationReservation(UUID clusterId, UUID nodeId) {
        int nodeSlots = runningSynchronousOrchestratorDepth("te.node_id", nodeId);
        int clusterSlots = Math.max(
                runningSynchronousOrchestratorDepth("td.cluster_id", clusterId),
                queuedChildOfRunningParentSlots(clusterId));
        return new OrchestrationReservation(nodeSlots, clusterSlots);
    }

    private int runningSynchronousOrchestratorDepth(String identityColumn, UUID identity) {
        List<String> packages = TaskOrchestrationCatalog.synchronousDirectChildPackages().stream()
                .sorted()
                .toList();
        String placeholders = String.join(", ", java.util.Collections.nCopies(packages.size(), "?"));
        String sql = """
                SELECT ti.task_name, ts.script_package
                FROM task_execution te
                JOIN task_instance ti ON ti.id = te.task_instance_id
                JOIN task_definition td ON td.id = ti.task_definition_id
                JOIN task_step_definition ts ON ts.task_definition_id = td.id AND ts.enabled = TRUE
                WHERE te.status = 'RUNNING'
                  AND %s = ?
                  AND ti.task_name <> 'download_resolve_x_url'
                  AND ts.script_package IN (%s)
                """.formatted(identityColumn, placeholders);
        List<Object> parameters = new ArrayList<>();
        parameters.add(identity.toString());
        parameters.addAll(packages);
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new OrchestrationStep(
                        resultSet.getString("task_name"), resultSet.getString("script_package")),
                        parameters.toArray()).stream()
                .mapToInt(step -> TaskOrchestrationCatalog.maximumDescendantDepth(
                        step.taskName(), List.of(step.scriptPackage())))
                .max()
                .orElse(0);
    }

    private int queuedChildOfRunningParentSlots(UUID clusterId) {
        List<OrchestrationStep> steps = jdbcTemplate.query("""
                SELECT child.task_name, child_step.script_package
                FROM task_instance child
                JOIN task_definition child_definition ON child_definition.id = child.task_definition_id
                JOIN task_step_definition child_step
                  ON child_step.task_definition_id = child_definition.id AND child_step.enabled = TRUE
                WHERE child.status = 'QUEUED'
                  AND child_definition.cluster_id = ?
                  AND child.parent_task_instance_id IS NOT NULL
                  AND (child.available_at IS NULL OR child.available_at <= CURRENT_TIMESTAMP)
                  AND EXISTS (
                      SELECT 1 FROM task_execution parent_execution
                      WHERE parent_execution.task_instance_id = child.parent_task_instance_id
                        AND parent_execution.status = 'RUNNING'
                  )
                """, (resultSet, rowNumber) -> new OrchestrationStep(
                resultSet.getString("task_name"), resultSet.getString("script_package")),
                clusterId.toString());
        return steps.stream()
                .mapToInt(step -> 1 + TaskOrchestrationCatalog.maximumDescendantDepth(
                        step.taskName(), List.of(step.scriptPackage())))
                .max()
                .orElse(0);
    }

    private int count(String sql, String id) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return count == null ? 0 : count;
    }

    private TaskScope taskScope(ClaimTaskRequest request, String taskAlias, boolean releaseDraining) {
        StringBuilder condition = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        if (releaseDraining) {
            // 发布排空期只允许本节点已运行父任务的直接子任务，兼容旧 Executor 的无范围请求。
            condition.append("  AND ").append(taskAlias).append(".parent_task_instance_id IN (\n")
                    .append("      SELECT draining_parent.task_instance_id FROM task_execution draining_parent\n")
                    .append("      WHERE draining_parent.node_id = ? AND draining_parent.status = 'RUNNING'\n")
                    .append("  )\n");
            parameters.add(request.nodeId().toString());
        }
        if (!request.parentTaskInstanceIds().isEmpty()) {
            String placeholders = String.join(", ", java.util.Collections.nCopies(
                    request.parentTaskInstanceIds().size(), "?"));
            condition.append("  AND ").append(taskAlias).append(".parent_task_instance_id IN (")
                    .append(placeholders).append(")\n");
            parameters.addAll(request.parentTaskInstanceIds().stream()
                    .map(UUID::toString)
                    .map(value -> (Object) value)
                    .toList());
        } else if (request.rootTaskOnly()) {
            condition.append("  AND ").append(taskAlias).append(".parent_task_instance_id IS NULL\n");
        } else if (request.childTaskOnly()) {
            condition.append("  AND ").append(taskAlias).append(".parent_task_instance_id IS NOT NULL\n");
        }
        return new TaskScope(condition.toString(), List.copyOf(parameters));
    }

    /**
     * 续期执行租约并返回取消状态。
     *
     * @param executionId 执行标识
     * @param request 续期请求
     * @return 租约状态
     */
    @Transactional
    public LeaseHeartbeatView heartbeat(UUID executionId, LeaseHeartbeatRequest request) {
        LeaseHeartbeatView authorized = executionAuthorizationService.renewIfProtected(executionId, request);
        if (authorized != null) {
            // 正文任务的续租和 generation 轮换同事务完成，不能走旧协议复活失效租约。
            return authorized;
        }
        Instant leaseUntil = Instant.now().plusSeconds(request.leaseSeconds());
        int updated = jdbcTemplate.update("""
                UPDATE task_execution SET lease_until = ?, last_heartbeat_at = ?, updated_at = ?
                WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
                """, Timestamp.from(leaseUntil), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), executionId.toString(),
                request.leaseToken().toString());
        if (updated != 1) {
            throw new IllegalArgumentException("Active execution lease does not exist");
        }
        String taskStatus = jdbcTemplate.queryForObject("""
                SELECT ti.status FROM task_execution te
                JOIN task_instance ti ON ti.id = te.task_instance_id
                WHERE te.id = ?
                """, String.class, executionId.toString());
        return new LeaseHeartbeatView(leaseUntil, TaskStatus.CANCELLING.name().equals(taskStatus), "ACTIVE");
    }

    /**
     * 上报一个脚本步骤的最终结果。
     *
     * @param executionId 执行标识
     * @param request 结果请求
     */
    @Transactional
    public ExecutionReportView reportStep(UUID executionId, ReportStepExecutionRequest request) {
        if (request.status() != TaskStatus.SUCCEEDED && request.status() != TaskStatus.FAILED
                && request.status() != TaskStatus.CANCELLED && request.status() != TaskStatus.TIMED_OUT) {
            throw new IllegalArgumentException("Step result status is not terminal");
        }
        validateLogIndex(request.logIndex());
        validateTerminalStepResult(request);
        StepReport existing = findStepReport(executionId, request.stepDefinitionId(), request.attempt());
        if (existing != null) {
            requireExecutionToken(executionId, request.leaseToken());
            return replayStepReport(existing, request);
        }
        requireLease(executionId, request.leaseToken());
        Instant now = Instant.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO step_execution (
                        id, task_execution_id, step_definition_id, attempt, status, exit_code, result_json,
                        error_code, error_message, log_index_json, started_at, finished_at, created_at, updated_at,
                        report_request_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), executionId.toString(), request.stepDefinitionId().toString(),
                    request.attempt(), request.status().name(), request.exitCode(), jsonColumnMapper.write(request.result()),
                    request.errorCode(), truncate(request.errorMessage(), 2048),
                    jsonColumnMapper.write(request.logIndex()), Timestamp.from(now), Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now), request.reportRequestId().toString());
            return ExecutionReportView.accepted();
        } catch (DuplicateKeyException exception) {
            StepReport concurrent = findStepReport(executionId, request.stepDefinitionId(), request.attempt());
            if (concurrent != null) {
                return replayStepReport(concurrent, request);
            }
            throw reportConflict("Step report request identifier is already used");
        }
    }

    /**
     * 完成任务执行并同步任务实例最终状态。
     *
     * @param executionId 执行标识
     * @param request 完成请求
     */
    @Transactional
    public ExecutionReportView complete(UUID executionId, CompleteExecutionRequest request) {
        if (request.status() != TaskStatus.SUCCEEDED && request.status() != TaskStatus.FAILED
                && request.status() != TaskStatus.CANCELLED && request.status() != TaskStatus.TIMED_OUT) {
            throw new IllegalArgumentException("Execution status is not terminal");
        }
        validateCompensation(request);
        executionAuthorizationService.lockMutationIfProtected(executionId);
        ExecutionState current = executionState(executionId, request.leaseToken());
        if (!"RUNNING".equals(current.status())) {
            if (completionMatches(current, request)) {
                return ExecutionReportView.replayedResult();
            }
            if (current.completionRequestId() == null) {
                // 回收器或截止时间服务终结的执行没有 Executor 完成请求，迟到写统一按失租处理。
                throw leaseLost();
            }
            throw reportConflict("Execution completion conflicts with the stored terminal state");
        }
        if (current.leaseUntil().isBefore(Instant.now())) {
            throw leaseLost();
        }
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE task_execution SET status = ?, finished_at = ?, updated_at = ?, completion_request_id = ?,
                    compensation_status = ?, compensation_required = ?, compensation_error_code = ?
                WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
                """, request.status().name(), Timestamp.from(now), Timestamp.from(now),
                request.completionRequestId().toString(), request.normalizedCompensationStatus().name(),
                request.compensationRequired(), request.compensationErrorCode(), executionId.toString(),
                request.leaseToken().toString());
        if (updated != 1) {
            ExecutionState concurrent = executionState(executionId, request.leaseToken());
            if (completionMatches(concurrent, request)) {
                return ExecutionReportView.replayedResult();
            }
            throw reportConflict("Execution state changed concurrently");
        }
        executionAuthorizationService.revokeExecution(executionId);
        ExecutionIdentity identity = jdbcTemplate.queryForObject("""
                SELECT te.task_instance_id, te.execution_target_id, ti.status AS task_status
                FROM task_execution te
                JOIN task_instance ti ON ti.id = te.task_instance_id
                WHERE te.id = ?
                """, (resultSet, rowNumber) -> new ExecutionIdentity(
                UUID.fromString(resultSet.getString("task_instance_id")),
                resultSet.getString("execution_target_id") == null ? null
                        : UUID.fromString(resultSet.getString("execution_target_id")),
                resultSet.getString("task_status")
        ), executionId.toString());
        if (identity == null) {
            throw new IllegalStateException("Execution identity does not exist");
        }
        if (request.compensationRequired()
                && request.normalizedCompensationStatus() == CompensationStatus.FAILED) {
            // 专用事件与执行终态处于同一事务，Relay 可据此触发告警或人工处理工作流。
            taskEventService.appendCompensationRequired(identity.taskInstanceId(), request.status().name(),
                    executionId.toString(), now);
        }
        if (identity.targetId() != null) {
            jdbcTemplate.update("""
                    UPDATE task_execution_target SET status = ?, updated_at = ?
                    WHERE id = ? AND status = 'RUNNING'
                    """, request.status().name(), Timestamp.from(now), identity.targetId().toString());
            multiNodeTaskAggregationService.aggregate(identity.taskInstanceId(), now);
            childTaskAggregationService.aggregateParentChain(identity.taskInstanceId(), now);
            cancellationPropagationService.finalizeCancellationChain(identity.taskInstanceId());
            return ExecutionReportView.accepted();
        }
        childTaskAggregationService.completeOrWait(identity.taskInstanceId(), identity.taskStatus(),
                request.status(), executionId.toString(), now);
        cancellationPropagationService.finalizeCancellationChain(identity.taskInstanceId());
        return ExecutionReportView.accepted();
    }

    private void validateCompensation(CompleteExecutionRequest request) {
        CompensationStatus status = request.normalizedCompensationStatus();
        if (request.status() == TaskStatus.SUCCEEDED && status != CompensationStatus.NOT_REQUIRED) {
            throw new IllegalArgumentException("Successful execution cannot have compensation");
        }
        if (status != CompensationStatus.FAILED
                && (request.compensationRequired() || request.compensationErrorCode() != null)) {
            throw new IllegalArgumentException("Only failed compensation can require attention or carry an error");
        }
        if (request.compensationErrorCode() != null
                && !request.compensationErrorCode().matches("^[A-Z][A-Z0-9_]{2,127}$")) {
            throw new IllegalArgumentException("Compensation error code is invalid");
        }
    }

    private ClaimedTaskView createExecution(ClaimTaskRequest request, UUID taskId, UUID targetId,
                                            ExecutionTarget target) {
        UUID executionId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        Long currentFencingToken = jdbcTemplate.queryForObject(
                "SELECT MAX(fencing_token) FROM task_execution WHERE task_instance_id = ?",
                Long.class, taskId.toString());
        long fencingToken = currentFencingToken == null ? 1 : currentFencingToken + 1;
        Instant now = Instant.now();
        Instant leaseUntil = now.plusSeconds(request.leaseSeconds());
        jdbcTemplate.update("""
                INSERT INTO task_execution
                (id, task_instance_id, node_id, status, lease_token, lease_until, started_at, created_at, updated_at,
                 execution_target_id, fencing_token, last_heartbeat_at, claim_request_id)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, executionId.toString(), taskId.toString(), request.nodeId().toString(), leaseToken.toString(),
                Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                targetId == null ? null : targetId.toString(), fencingToken, Timestamp.from(now),
                request.claimRequestId().toString());
        return buildClaimedTaskView(request.nodeId(), taskId, target, executionId, leaseToken, fencingToken, leaseUntil);
    }

    private Optional<ClaimedTaskView> findClaimByRequest(ClaimTaskRequest request) {
        List<ExistingClaim> claims = jdbcTemplate.query("""
                SELECT id, task_instance_id, execution_target_id, lease_token, fencing_token, lease_until
                FROM task_execution
                WHERE claim_request_id = ? AND node_id = ?
                """, (resultSet, rowNumber) -> new ExistingClaim(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("task_instance_id")),
                resultSet.getString("execution_target_id") == null ? null
                        : UUID.fromString(resultSet.getString("execution_target_id")),
                UUID.fromString(resultSet.getString("lease_token")),
                resultSet.getLong("fencing_token"), resultSet.getTimestamp("lease_until").toInstant()),
                request.claimRequestId().toString(), request.nodeId().toString());
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        ExistingClaim claim = claims.getFirst();
        ExecutionTarget target = claim.targetId() == null ? null : loadExecutionTarget(claim.targetId());
        return Optional.of(buildClaimedTaskView(request.nodeId(), claim.taskId(), target, claim.executionId(),
                claim.leaseToken(), claim.fencingToken(), claim.leaseUntil()));
    }

    private ExecutionTarget loadExecutionTarget(UUID targetId) {
        return jdbcTemplate.queryForObject("""
                SELECT et.id, et.task_instance_id, et.target_index, et.target_count, td.execution_mode,
                       ti.required_node_labels_json
                FROM task_execution_target et
                JOIN task_instance ti ON ti.id = et.task_instance_id
                JOIN task_definition td ON td.id = ti.task_definition_id
                WHERE et.id = ?
                """, (resultSet, rowNumber) -> new ExecutionTarget(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("task_instance_id")),
                resultSet.getInt("target_index"), resultSet.getInt("target_count"),
                resultSet.getString("execution_mode"),
                jsonColumnMapper.read(resultSet.getString("required_node_labels_json"))), targetId.toString());
    }

    private ClaimedTaskView buildClaimedTaskView(UUID nodeId, UUID taskId, ExecutionTarget target,
                                                 UUID executionId, UUID leaseToken, long fencingToken,
                                                 Instant leaseUntil) {
        var task = instanceRepository.findById(taskId).orElseThrow();
        String definitionIdText = jdbcTemplate.queryForObject(
                "SELECT task_definition_id FROM task_instance WHERE id = ?", String.class, taskId.toString());
        UUID definitionId = UUID.fromString(definitionIdText);
        Long timeoutSeconds = jdbcTemplate.queryForObject(
                "SELECT timeout_seconds FROM task_definition WHERE id = ?", Long.class, definitionId.toString());
        Integer definitionVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM task_definition WHERE id = ?", Integer.class, definitionId.toString());
        List<ClaimedStepView> steps = stepRepository.list(definitionId).stream()
                .filter(step -> step.enabled())
                .map(step -> new ClaimedStepView(
                        step.id(), step.name(), step.stepKind(), step.scriptPackage(), step.scriptVersion(),
                        scriptReleaseDigest(nodeId, step.scriptPackage(), step.scriptVersion()),
                        step.entrypoint(), step.argumentsTemplate(), step.timeoutSeconds(), step.failurePolicy(),
                        step.sequenceNumber(), step.maxAttempts()))
                .toList();
        List<String> scriptPackages = steps.stream().map(ClaimedStepView::scriptPackage).toList();
        boolean mayCreateChildren = TaskOrchestrationCatalog.mayCreateChildren(
                task.taskName(), scriptPackages);
        Map<String, Object> parameters = new LinkedHashMap<>(task.parameters());
        if (target != null) {
            Map<String, Object> executionTarget = new LinkedHashMap<>();
            executionTarget.put("mode", target.executionMode());
            executionTarget.put("index", target.targetIndex());
            executionTarget.put("count", target.targetCount());
            executionTarget.put("nodeId", nodeId.toString());
            parameters.put("taskExecutionTarget", executionTarget);
        }
        String definitionDigest = definitionDigest(definitionId, definitionVersion, task.taskName(), steps);
        var claimed = new ClaimedTaskView(executionId, task.id(), task.parentTaskInstanceId(), task.taskName(),
                definitionId, definitionVersion, definitionDigest, leaseToken,
                fencingToken,
                leaseUntil, task.startedAt().plusSeconds(timeoutSeconds == null ? 1 : timeoutSeconds),
                mayCreateChildren, parameters, steps);
        return claimed.withWorkloadAuthorization(executionAuthorizationService.issue(claimed));
    }

    private String definitionDigest(UUID definitionId, int version, String taskName, List<ClaimedStepView> steps) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, definitionId.toString());
            updateDigest(digest, Integer.toString(version));
            updateDigest(digest, taskName);
            for (ClaimedStepView step : steps) {
                updateDigest(digest, step.stepDefinitionId().toString());
                updateDigest(digest, step.name());
                updateDigest(digest, step.stepKind().name());
                updateDigest(digest, step.scriptPackage());
                updateDigest(digest, step.scriptVersion());
                updateDigest(digest, step.scriptReleaseDigest());
                updateDigest(digest, step.entrypoint());
                step.argumentsTemplate().forEach(value -> updateDigest(digest, value));
                updateDigest(digest, Long.toString(step.timeoutSeconds()));
                updateDigest(digest, step.failurePolicy().name());
                updateDigest(digest, Integer.toString(step.sequenceNumber()));
                updateDigest(digest, Integer.toString(step.maxAttempts()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String scriptReleaseDigest(UUID nodeId, String packageName, String version) {
        String capabilitiesJson = jdbcTemplate.queryForObject(
                "SELECT capabilities_json FROM executor_node WHERE id = ?", String.class, nodeId.toString());
        Map<String, Object> capabilities = jsonColumnMapper.read(capabilitiesJson);
        Object inventory = capabilities.get("scriptReleases");
        if (inventory == null) {
            // 滚动升级期间兼容尚未上报发布清单的旧 Executor。
            return null;
        }
        if (!(inventory instanceof Map<?, ?> releases)) {
            throw new IllegalStateException("Executor script release inventory is invalid");
        }
        Object digest = releases.get(packageName + ":" + version);
        if (!(digest instanceof String value) || !value.matches("^[a-f0-9]{64}$")) {
            throw new IllegalStateException("Executor does not contain the required script release");
        }
        return value;
    }

    private boolean lockAndValidateNodeInstance(UUID nodeId, UUID instanceId) {
        // 节点行锁覆盖整个领取事务，使排空更新不能插入校验与执行记录创建之间。
        List<NodeClaimState> activeNodes = jdbcTemplate.query("""
                SELECT id, status, status_reason FROM executor_node
                WHERE id = ? AND instance_id = ? AND enabled = TRUE
                  AND (status IN ('ONLINE', 'BUSY')
                       OR (status = 'DRAINING' AND status_reason = 'QQ_FLOW_RELEASE'))
                  AND last_heartbeat_at >= ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new NodeClaimState(
                        resultSet.getString("status"), resultSet.getString("status_reason")),
                nodeId.toString(), instanceId.toString(),
                Timestamp.from(Instant.now().minusSeconds(nodeHealthProperties.offlineAfterSeconds())));
        if (activeNodes.size() != 1) {
            throw new IllegalArgumentException("Active executor node instance does not exist");
        }
        NodeClaimState node = activeNodes.getFirst();
        return "DRAINING".equals(node.status()) && "QQ_FLOW_RELEASE".equals(node.reason());
    }

    private Map<String, Object> nodeLabels(UUID nodeId) {
        String labels = jdbcTemplate.queryForObject(
                "SELECT labels_json FROM executor_node WHERE id = ?", String.class, nodeId.toString());
        return jsonColumnMapper.read(labels);
    }

    private boolean matchesRequiredLabels(Map<String, Object> required, Map<String, Object> actual) {
        return required == null || required.entrySet().stream()
                .allMatch(entry -> java.util.Objects.equals(actual.get(entry.getKey()), entry.getValue()));
    }

    private void requireLease(UUID executionId, UUID leaseToken) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_execution
                WHERE id = ? AND lease_token = ? AND status = 'RUNNING' AND lease_until >= ?
                """, Integer.class, executionId.toString(), leaseToken.toString(), Timestamp.from(Instant.now()));
        if (count == null || count != 1) {
            throw leaseLost();
        }
    }

    private void requireExecutionToken(UUID executionId, UUID leaseToken) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_execution WHERE id = ? AND lease_token = ?
                """, Integer.class, executionId.toString(), leaseToken.toString());
        if (count == null || count != 1) {
            throw leaseLost();
        }
    }

    private void validateTerminalStepResult(ReportStepExecutionRequest request) {
        if (request.status() != TaskStatus.SUCCEEDED) {
            return;
        }
        List<String> contracts = jdbcTemplate.query("""
                SELECT td.result_schema
                FROM task_step_definition current_step
                JOIN task_definition td ON td.id = current_step.task_definition_id
                WHERE current_step.id = ? AND current_step.step_kind = 'NORMAL'
                  AND current_step.sequence_number = (
                      SELECT MAX(last_step.sequence_number) FROM task_step_definition last_step
                      WHERE last_step.task_definition_id = current_step.task_definition_id
                        AND last_step.step_kind = 'NORMAL' AND last_step.enabled = TRUE
                  )
                """, (resultSet, rowNumber) -> resultSet.getString("result_schema"),
                request.stepDefinitionId().toString());
        if (!contracts.isEmpty()) {
            schemaValidationService.validateResult(jsonColumnMapper.read(contracts.getFirst()), request.result());
        }
    }

    private void validateLogIndex(Map<String, Object> logIndex) {
        if (!logIndex.keySet().stream().allMatch(key -> "stdout".equals(key) || "stderr".equals(key))) {
            throw new IllegalArgumentException("Step log index contains an unsupported stream");
        }
        validateLogSegments(logIndex.getOrDefault("stdout", List.of()), "stdout");
        validateLogSegments(logIndex.getOrDefault("stderr", List.of()), "stderr");
    }

    private void validateLogSegments(Object value, String streamName) {
        if (!(value instanceof List<?> segments) || segments.size() > LOG_SEGMENT_MAX_COUNT) {
            throw new IllegalArgumentException("Step log index segment list is invalid");
        }
        for (Object item : segments) {
            if (!(item instanceof Map<?, ?> segment)
                    || !segment.keySet().stream().allMatch(key -> "path".equals(key)
                    || "sizeBytes".equals(key) || "sha256".equals(key))) {
                throw new IllegalArgumentException("Step log index segment is invalid");
            }
            Object path = segment.get("path");
            Object size = segment.get("sizeBytes");
            Object digest = segment.get("sha256");
            if (!(path instanceof String pathValue)
                    || !pathValue.matches("^" + streamName + "-[0-9]{6}\\.log$")
                    || !(size instanceof Number sizeValue) || sizeValue.longValue() < 0
                    || sizeValue.longValue() > LOG_SEGMENT_MAX_BYTES
                    || !(digest instanceof String digestValue) || !digestValue.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("Step log index segment metadata is invalid");
            }
        }
    }

    private StepReport findStepReport(UUID executionId, UUID stepDefinitionId, int attempt) {
        List<StepReport> reports = jdbcTemplate.query("""
                SELECT report_request_id, status, exit_code, result_json, error_code, error_message, log_index_json
                FROM step_execution
                WHERE task_execution_id = ? AND step_definition_id = ? AND attempt = ?
                """, (resultSet, rowNumber) -> new StepReport(
                resultSet.getString("report_request_id"), resultSet.getString("status"),
                (Integer) resultSet.getObject("exit_code"), resultSet.getString("result_json"),
                resultSet.getString("error_code"), resultSet.getString("error_message"),
                resultSet.getString("log_index_json")),
                executionId.toString(), stepDefinitionId.toString(), attempt);
        return reports.isEmpty() ? null : reports.getFirst();
    }

    private ExecutionReportView replayStepReport(StepReport existing, ReportStepExecutionRequest request) {
        boolean matches = request.reportRequestId().toString().equals(existing.reportRequestId())
                && request.status().name().equals(existing.status())
                && java.util.Objects.equals(request.exitCode(), existing.exitCode())
                && jsonColumnMapper.write(request.result()).equals(existing.resultJson())
                && java.util.Objects.equals(request.errorCode(), existing.errorCode())
                && java.util.Objects.equals(truncate(request.errorMessage(), 2048), existing.errorMessage())
                && ((existing.logIndexJson() == null && request.logIndex().isEmpty())
                    || jsonColumnMapper.write(request.logIndex()).equals(existing.logIndexJson()));
        if (!matches) {
            throw reportConflict("Step report conflicts with the stored attempt");
        }
        return ExecutionReportView.replayedResult();
    }

    private ExecutionState executionState(UUID executionId, UUID leaseToken) {
        List<ExecutionState> states = jdbcTemplate.query("""
                SELECT status, lease_until, completion_request_id, compensation_status,
                       compensation_required, compensation_error_code
                FROM task_execution WHERE id = ? AND lease_token = ?
                """, (resultSet, rowNumber) -> new ExecutionState(
                resultSet.getString("status"), resultSet.getTimestamp("lease_until").toInstant(),
                resultSet.getString("completion_request_id"),
                CompensationStatus.valueOf(resultSet.getString("compensation_status")),
                resultSet.getBoolean("compensation_required"),
                resultSet.getString("compensation_error_code")), executionId.toString(), leaseToken.toString());
        if (states.isEmpty()) {
            throw leaseLost();
        }
        return states.getFirst();
    }

    private boolean completionMatches(ExecutionState current, CompleteExecutionRequest request) {
        return current.status().equals(request.status().name())
                && request.completionRequestId().toString().equals(current.completionRequestId())
                && current.compensationStatus() == request.normalizedCompensationStatus()
                && current.compensationRequired() == request.compensationRequired()
                && java.util.Objects.equals(current.compensationErrorCode(), request.compensationErrorCode());
    }

    private SchedulerException leaseLost() {
        return new SchedulerException(ErrorCode.EXECUTION_LEASE_LOST, HttpStatus.CONFLICT,
                "Active execution lease does not exist");
    }

    private SchedulerException reportConflict(String message) {
        return new SchedulerException(ErrorCode.REPORT_CONFLICT, HttpStatus.CONFLICT, message);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record CapacityDefinition(UUID definitionId, UUID clusterId, int maxConcurrency,
                                      int clusterMaxConcurrency) {
    }

    private record TaskCapacityRequirement(int minimumSlots, boolean rootTask) {
    }

    private record ExecutionCapacity(boolean available, boolean theoreticallyFits,
                                     int clusterRemaining, int nodeRemaining) {
    }

    private record OrchestrationReservation(int nodeSlots, int clusterSlots) {
    }

    private record OrchestrationStep(String taskName, String scriptPackage) {
    }

    private enum CapacityDecision {
        AVAILABLE,
        RESERVE_FOR_ORCHESTRATOR,
        UNAVAILABLE
    }

    private record ExecutionTarget(UUID id, UUID taskInstanceId, int targetIndex, int targetCount,
                                   String executionMode, Map<String, Object> requiredLabels) {
    }

    private record PlacementCandidate(UUID taskId, boolean rootTask, Map<String, Object> requiredLabels,
                                      int priority, Instant createdAt) {
    }

    private record PlacementCursor(int priority, Instant createdAt, UUID taskId) {

        private static PlacementCursor after(PlacementCandidate candidate) {
            return new PlacementCursor(candidate.priority(), candidate.createdAt(), candidate.taskId());
        }
    }

    private record TaskScope(String condition, List<Object> parameters) {
    }

    private record NodeClaimState(String status, String reason) {
    }

    private record EligibleNode(UUID nodeId, Map<String, Object> labels, Map<String, Object> requiredLabels) {
    }

    private record ExecutionIdentity(UUID taskInstanceId, UUID targetId, String taskStatus) {
    }

    private record ExistingClaim(UUID executionId, UUID taskId, UUID targetId, UUID leaseToken,
                                 long fencingToken, Instant leaseUntil) {
    }

    private record StepReport(String reportRequestId, String status, Integer exitCode, String resultJson,
                              String errorCode, String errorMessage, String logIndexJson) {
    }

    private record ExecutionState(String status, Instant leaseUntil, String completionRequestId,
                                  CompensationStatus compensationStatus, boolean compensationRequired,
                                  String compensationErrorCode) {
    }

}
