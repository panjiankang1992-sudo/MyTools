package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ClaimCapacityReservedException;
import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.ExecutionCompletion;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.client.SchedulerClientException;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorDiskProperties;
import com.yuyutian.mytools.task.executor.runtime.adaptation.NovelAdaptationTaskHost;
import com.yuyutian.mytools.task.executor.client.adaptation.ReaderAdaptationException;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import com.yuyutian.mytools.task.executor.node.ExecutorNodeAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 任务领取和脚本步骤执行工作器。
 */
@Component
public class TaskExecutionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutionWorker.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ExecutorProperties properties;
    private final ExecutorNodeAgent nodeAgent;
    private final SchedulerClient schedulerClient;
    private final ScriptProcessRunner processRunner;
    private final ScriptReleaseVerifier releaseVerifier;
    private final ExecutionReportJournal reportJournal;
    private final DiskSpaceGuard diskSpaceGuard;
    private final ObjectMapper objectMapper;
    private final NovelAdaptationTaskHost adaptationHost;
    private final AtomicInteger runningTasks = new AtomicInteger();
    private final Map<UUID, RunningTask> runningTaskIndex = new ConcurrentHashMap<>();
    private final AtomicBoolean preferChildTakeover = new AtomicBoolean(true);
    private final AtomicReference<UnorchestratedClaimScope> capacityReservationScope = new AtomicReference<>();
    private final AtomicInteger replayFailures = new AtomicInteger();
    private volatile Instant nextReplayAt = Instant.EPOCH;

    /**
     * 创建任务执行工作器。
     *
     * @param properties 节点配置
     * @param nodeAgent 节点代理
     * @param schedulerClient 调度服务客户端
     * @param processRunner 脚本进程运行器
     * @param releaseVerifier 脚本发布完整性验证器
     * @param objectMapper JSON 映射器
     */
    public TaskExecutionWorker(ExecutorProperties properties, ExecutorNodeAgent nodeAgent,
                               SchedulerClient schedulerClient, ScriptProcessRunner processRunner,
                               ScriptReleaseVerifier releaseVerifier, ObjectMapper objectMapper) {
        this(properties, nodeAgent, schedulerClient, processRunner, releaseVerifier, objectMapper,
                new ExecutionReportJournal(properties, objectMapper),
                new DiskSpaceGuard(properties, new ExecutorDiskProperties(0, 0)));
    }

    /**
     * 创建带持久上报日志的任务执行工作器。
     *
     * @param properties 节点配置
     * @param nodeAgent 节点代理
     * @param schedulerClient 调度服务客户端
     * @param processRunner 脚本进程运行器
     * @param releaseVerifier 脚本发布完整性验证器
     * @param objectMapper JSON 映射器
     * @param reportJournal 执行结果持久上报日志
     */
    public TaskExecutionWorker(ExecutorProperties properties, ExecutorNodeAgent nodeAgent,
                               SchedulerClient schedulerClient, ScriptProcessRunner processRunner,
                               ScriptReleaseVerifier releaseVerifier, ObjectMapper objectMapper,
                               ExecutionReportJournal reportJournal) {
        this(properties, nodeAgent, schedulerClient, processRunner, releaseVerifier, objectMapper, reportJournal,
                new DiskSpaceGuard(properties, new ExecutorDiskProperties(0, 0)));
    }

    /**
     * 创建带持久日志和磁盘空间守卫的任务执行工作器。
     *
     * @param properties 节点配置
     * @param nodeAgent 节点代理
     * @param schedulerClient 调度服务客户端
     * @param processRunner 脚本进程运行器
     * @param releaseVerifier 脚本发布完整性验证器
     * @param objectMapper JSON 映射器
     * @param reportJournal 执行结果持久上报日志
     * @param diskSpaceGuard 工作目录磁盘守卫
     */
    public TaskExecutionWorker(ExecutorProperties properties, ExecutorNodeAgent nodeAgent,
                               SchedulerClient schedulerClient, ScriptProcessRunner processRunner,
                               ScriptReleaseVerifier releaseVerifier, ObjectMapper objectMapper,
                               ExecutionReportJournal reportJournal, DiskSpaceGuard diskSpaceGuard) {
        this(properties, nodeAgent, schedulerClient, processRunner, releaseVerifier, objectMapper, reportJournal, diskSpaceGuard, null);
    }

    /** 创建带专属小说改编入口的工作器，旧构造保留兼容但不能执行受保护任务。 */
    @Autowired
    public TaskExecutionWorker(ExecutorProperties properties, ExecutorNodeAgent nodeAgent,
                               SchedulerClient schedulerClient, ScriptProcessRunner processRunner,
                               ScriptReleaseVerifier releaseVerifier, ObjectMapper objectMapper,
                               ExecutionReportJournal reportJournal, DiskSpaceGuard diskSpaceGuard, NovelAdaptationTaskHost adaptationHost) {
        this.properties = properties;
        this.nodeAgent = nodeAgent;
        this.schedulerClient = schedulerClient;
        this.processRunner = processRunner;
        this.releaseVerifier = releaseVerifier;
        this.objectMapper = objectMapper;
        this.reportJournal = reportJournal;
        this.diskSpaceGuard = diskSpaceGuard;
        this.adaptationHost = adaptationHost;
    }

    /**
     * 在节点有剩余容量时领取一个任务。
     */
    @Scheduled(fixedDelayString = "#{@executorPollInterval.milliseconds()}", initialDelay = 250)
    public void poll() {
        ExecutorNodeRegistration registration = nodeAgent.registration();
        if (registration == null || runningTasks.get() >= properties.maxConcurrentTasks()) {
            return;
        }
        if (Instant.now().isBefore(nextReplayAt)) {
            return;
        }
        try {
            // 每次领取前先清空待上报日志，避免重启后执行新任务却遗留旧终态。
            reportJournal.replayPending(schedulerClient);
            // 完成 ACK 后再清理成功任务目录，进程在 ACK 与清理之间退出时也可在此恢复。
            reportJournal.cleanupAcknowledgedWorkDirectories();
            replayFailures.set(0);
            nextReplayAt = Instant.EPOCH;
            if (!diskSpaceGuard.hasCapacity()) {
                // 低磁盘时仍允许 WAL 回放和成功目录清理，但禁止扩大本地执行现场。
                nodeAgent.drainForDiskPressure();
                return;
            }
            int claimBudget = properties.maxConcurrentTasks() - runningTasks.get();
            for (int claimedCount = 0;
                 claimedCount < claimBudget && runningTasks.get() < properties.maxConcurrentTasks();
                 claimedCount++) {
                Optional<ClaimedTask> claimed = claimNextTask(registration);
                if (claimed.isEmpty()) {
                    return;
                }
                ClaimedTask task = claimed.get();
                // 领取成功后先记录活动执行，再启动任务线程，单次轮询应立即填满空闲并发槽。
                reportJournal.recordClaim(task);
                runningTaskIndex.put(task.taskInstanceId(), runningTask(task));
                runningTasks.incrementAndGet();
                nodeAgent.setRunningTasks(runningTasks.get());
                Thread.startVirtualThread(() -> execute(task));
            }
        } catch (ClaimCapacityReservedException exception) {
            // 容量保留是正常调度控制信号，不对轮询做故障退避。
            replayFailures.set(0);
            nextReplayAt = Instant.EPOCH;
        } catch (IOException exception) {
            if (exception instanceof ReportRetryDeferredException deferred) {
                // WAL 是上报退避时间的唯一权威，避免再叠加一层进程内退避和抖动。
                nextReplayAt = deferred.retryAt();
                LOGGER.warn("Task claim deferred by pending report WAL until {}", deferred.retryAt());
                return;
            }
            int failures = Math.min(replayFailures.incrementAndGet(), 5);
            long baseDelayMillis = Math.min(30_000L, 1_000L << failures);
            long jitterMillis = ThreadLocalRandom.current().nextLong(Math.max(1L, baseDelayMillis / 4L));
            nextReplayAt = Instant.now().plusMillis(baseDelayMillis + jitterMillis);
            LOGGER.warn("Task claim failed: {}", exception.getMessage());
        }
    }

    /**
     * 返回当前仍在执行或收敛最终上报的任务数量。
     *
     * @return 运行中的任务数量
     */
    public int runningTaskCount() {
        return runningTasks.get();
    }

    private Optional<ClaimedTask> claimNextTask(ExecutorNodeRegistration registration) throws IOException {
        int maximumTasks = properties.maxConcurrentTasks();
        int reservedChildSlots = properties.effectiveReservedChildTaskSlots();
        if (reservedChildSlots == 0) {
            // 单槽节点无法同时运行阻塞父任务和子任务，维持旧领取行为以免根任务永久饥饿。
            return schedulerClient.claim(registration.id(), nodeAgent.instanceId());
        }
        boolean hasOrchestrator = runningTaskIndex.values().stream().anyMatch(RunningTask::mayCreateChildren);
        if (!hasOrchestrator) {
            UnorchestratedClaimScope reservedScope = capacityReservationScope.get();
            if (reservedScope != null) {
                Optional<ClaimedTask> reservedTask = claimUnorchestratedScope(registration, reservedScope);
                // 无保留信号的响应说明目标已领取或阻塞已消失，可以恢复根与子队列公平轮换。
                capacityReservationScope.compareAndSet(reservedScope, null);
                if (reservedTask.isPresent()) {
                    return reservedTask;
                }
            }
            // 没有阻塞编排任务时交替领取子任务和根任务，叶子任务可填满全部槽位且两类队列不饥饿。
            boolean childFirst = nextUnorchestratedClaimPrefersChild();
            UnorchestratedClaimScope firstScope = childFirst
                    ? UnorchestratedClaimScope.CHILD : UnorchestratedClaimScope.ROOT;
            Optional<ClaimedTask> first = claimUnorchestratedScope(registration, firstScope);
            if (first.isPresent()) {
                return first;
            }
            UnorchestratedClaimScope secondScope = childFirst
                    ? UnorchestratedClaimScope.ROOT : UnorchestratedClaimScope.CHILD;
            return claimUnorchestratedScope(registration, secondScope);
        }
        for (Set<UUID> parentScope : runningOrchestratorScopesDeepestFirst()) {
            // 优先沿最深运行层向下领取，该层暂无候选时回退到浅层填充可执行兄弟。
            Optional<ClaimedTask> descendant = schedulerClient.claimDirectChildTask(
                    registration.id(), nodeAgent.instanceId(), parentScope);
            if (descendant.isPresent()) {
                return descendant;
            }
        }
        int rootTaskLimit = maximumTasks - reservedChildSlots;
        long localRootTasks = runningTaskIndex.values().stream()
                .filter(task -> task.depth() == 0 && task.mayCreateChildren())
                .count();
        if (localRootTasks < rootTaskLimit) {
            // 先确认当前编排链没有可运行后继，再用剩余根任务额度补充独立工作。
            return schedulerClient.claimRootTask(registration.id(), nodeAgent.instanceId());
        }
        // 本地编排链未结束时不接管无关子树，避免其阻塞父任务侵占后继槽位。
        return Optional.empty();
    }

    private Optional<ClaimedTask> claimUnorchestratedScope(ExecutorNodeRegistration registration,
                                                            UnorchestratedClaimScope scope) throws IOException {
        try {
            return scope == UnorchestratedClaimScope.CHILD
                    ? schedulerClient.claim(registration.id(), nodeAgent.instanceId(), true)
                    : schedulerClient.claimRootTask(registration.id(), nodeAgent.instanceId());
        } catch (ClaimCapacityReservedException exception) {
            // 记住产生保留信号的准确范围，远端父任务接管不能被无关根任务覆盖。
            capacityReservationScope.set(scope);
            throw exception;
        }
    }

    private boolean nextUnorchestratedClaimPrefersChild() {
        while (true) {
            boolean current = preferChildTakeover.get();
            if (preferChildTakeover.compareAndSet(current, !current)) {
                return current;
            }
        }
    }

    private List<Set<UUID>> runningOrchestratorScopesDeepestFirst() {
        return runningTaskIndex.entrySet().stream()
                .filter(entry -> entry.getValue().mayCreateChildren())
                .collect(java.util.stream.Collectors.groupingBy(
                        entry -> entry.getValue().depth(),
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.mapping(
                                Map.Entry::getKey,
                                java.util.stream.Collectors.toUnmodifiableSet())))
                .descendingMap()
                .values()
                .stream()
                .toList();
    }

    private enum UnorchestratedClaimScope {
        CHILD,
        ROOT
    }

    private RunningTask runningTask(ClaimedTask task) {
        RunningTask parent = task.parentTaskInstanceId() == null
                ? null : runningTaskIndex.get(task.parentTaskInstanceId());
        // 非本节点父任务的子任务作为新链入口，仍受本节点根任务额度约束。
        int depth = parent == null ? 0 : parent.depth() + 1;
        return new RunningTask(depth, task.mayCreateChildren());
    }

    private void execute(ClaimedTask task) {
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        AtomicBoolean monitorStopped = new AtomicBoolean();
        AtomicReference<Instant> leaseUntil = new AtomicReference<>(task.leaseUntil());
        Thread monitor = startLeaseMonitor(task, cancellationRequested, monitorStopped, leaseUntil);
        ExecutionCompletion completion = ExecutionCompletion.withoutCompensation("SUCCEEDED");
        try {
            StepOutcome outcome = executeNormalSteps(task, cancellationRequested, leaseUntil);
            CompensationOutcome compensation = executeScenarioSteps(task, outcome);
            completion = new ExecutionCompletion(outcome.status(), compensation.status(), compensation.required(),
                    compensation.errorCode());
        } catch (Exception exception) {
            completion = ExecutionCompletion.withoutCompensation(
                    cancellationRequested.get() ? "CANCELLED" : "FAILED");
            LOGGER.error("Task execution failed: taskInstanceId={}", task.taskInstanceId(), exception);
        } finally {
            monitorStopped.set(true);
            monitor.interrupt();
            // 即使终态上报 WAL 暂时失败，也先关闭普通正文能力。
            schedulerClient.releaseWorkloadAuthorization(task.executionId());
            try {
                reportJournal.persistCompletion(task, completion);
                // 统一从 WAL 按步骤优先、终态最后的顺序投递，禁止终态越过未确认步骤。
                reportJournal.replayPending(schedulerClient);
                reportJournal.cleanupAcknowledgedWorkDirectories();
            } catch (IOException exception) {
                LOGGER.error("Task completion report failed: taskInstanceId={}, status={}",
                        task.taskInstanceId(), completion.status(), exception);
            }
            runningTaskIndex.remove(task.taskInstanceId());
            runningTasks.decrementAndGet();
            nodeAgent.setRunningTasks(runningTasks.get());
        }
    }

    private StepOutcome executeNormalSteps(ClaimedTask task, AtomicBoolean cancellationRequested,
                                           AtomicReference<Instant> leaseUntil) throws IOException {
        if (isAdaptation(task)) {
            if (adaptationHost == null) throw new ReaderAdaptationException(ErrorCode.DISABLED, 0);
            adaptationHost.validate(task);
        } else if (adaptationHost != null && adaptationHost.dedicated()) {
            // 挂载私钥和结算内容的专属宿主不执行任何通用脚本或空步骤任务。
            throw new ReaderAdaptationException(ErrorCode.DISABLED, 0);
        }
        List<ClaimedStep> normalSteps = stepsOfKind(task, "NORMAL");
        Map<String, Object> stepOutputs = new LinkedHashMap<>();
        for (ClaimedStep step : normalSteps) {
            if (cancellationRequested.get() || !Instant.now().isBefore(leaseUntil.get())) {
                cancellationRequested.set(true);
                return new StepOutcome("CANCELLED", step, null, stepOutputs);
            }
            StepRun run = executeWithRetry(task, step, cancellationRequested::get, stepOutputs, true);
            stepOutputs.put(step.name(), run.outputs());
            if (run.status().equals("TIMED_OUT") && !"IGNORE".equals(step.failurePolicy())) {
                return new StepOutcome("TIMED_OUT", step, run, stepOutputs);
            }
            if (run.status().equals("CANCELLED")) {
                return new StepOutcome("CANCELLED", step, run, stepOutputs);
            }
            if (run.status().equals("FAILED") && !"IGNORE".equals(step.failurePolicy())) {
                return new StepOutcome("FAILED", step, run, stepOutputs);
            }
        }
        return new StepOutcome("SUCCEEDED", null, null, stepOutputs);
    }

    private CompensationOutcome executeScenarioSteps(ClaimedTask task, StepOutcome outcome) {
        String kind = switch (outcome.status()) {
            case "TIMED_OUT" -> "ON_TIMEOUT";
            case "CANCELLED" -> "ON_CANCEL";
            case "FAILED" -> "ON_FAILURE";
            default -> null;
        };
        if (kind == null) {
            return CompensationOutcome.notRequired();
        }
        List<ClaimedStep> scenarioSteps = stepsOfKind(task, kind);
        if (scenarioSteps.isEmpty()) {
            return CompensationOutcome.notRequired();
        }
        boolean failed = false;
        boolean required = false;
        String errorCode = null;
        for (ClaimedStep step : scenarioSteps) {
            try {
                StepRun run = executeWithRetry(task, step, () -> false, outcome.stepOutputs(), false);
                if (!"SUCCEEDED".equals(run.status())) {
                    // IGNORE 表示记录失败但不升级为需要人工处理，其余策略均视为必需补偿。
                    failed = true;
                    required |= !"IGNORE".equals(step.failurePolicy());
                    if (errorCode == null) {
                        errorCode = run.errorCode();
                    }
                }
            } catch (IOException exception) {
                failed = true;
                required |= !"IGNORE".equals(step.failurePolicy());
                if (errorCode == null) {
                    errorCode = "COMPENSATION_REPORT_FAILED";
                }
                LOGGER.error("Compensation step failed: executionId={}, step={}",
                        task.executionId(), step.name(), exception);
            }
        }
        return failed ? new CompensationOutcome("FAILED", required,
                errorCode == null ? "COMPENSATION_FAILED" : errorCode)
                : new CompensationOutcome("SUCCEEDED", false, null);
    }

    private StepRun executeWithRetry(ClaimedTask task, ClaimedStep step,
                                     java.util.function.BooleanSupplier cancellationRequested,
                                     Map<String, Object> stepOutputs, boolean enforceTaskDeadline) throws IOException {
        StepRun last = null;
        for (int attempt = 1; attempt <= step.maxAttempts(); attempt++) {
            last = executeStep(task, step, attempt, cancellationRequested, stepOutputs, enforceTaskDeadline);
            Map<String, Object> logIndex = objectMapper.convertValue(last.result().logIndex(), MAP_TYPE);
            UUID reportId = reportJournal.persistStep(task, step, attempt, last.status(), last.result().exitCode(),
                    last.outputs(), last.errorCode(), last.errorMessage(), logIndex);
            try {
                schedulerClient.reportStep(task, step, attempt, last.status(), last.result().exitCode(),
                        last.outputs(), last.errorCode(), last.errorMessage(), logIndex);
                reportJournal.acknowledge(reportId);
            } catch (SchedulerClientException exception) {
                if (!exception.retryable()
                        && !"REPORT_CONFLICT".equals(exception.errorCode())) {
                    // 明确的永久拒绝立即停止；幂等冲突允许 WAL 做一次有界重放后再判断。
                    throw exception;
                }
                LOGGER.warn("Step report deferred to WAL: executionId={}, step={}, attempt={}, errorCode={}",
                        task.executionId(), step.name(), attempt, exception.errorCode());
            } catch (IOException exception) {
                // 脚本结果已持久化，网络失败不能把成功脚本改判为失败，后续由 WAL 顺序重放。
                LOGGER.warn("Step report deferred to WAL: executionId={}, step={}, attempt={}, errorType={}",
                        task.executionId(), step.name(), attempt, exception.getClass().getSimpleName());
            }
            if (last.status().equals("SUCCEEDED") || last.status().equals("CANCELLED")
                    || !isRetryable(last)) {
                return last;
            }
            if (attempt < step.maxAttempts()) {
                waitBeforeStepRetry(attempt, cancellationRequested);
            }
        }
        return last;
    }

    private boolean isRetryable(StepRun run) {
        return run.retryable();
    }

    private void waitBeforeStepRetry(int attempt,
                                     java.util.function.BooleanSupplier cancellationRequested) throws IOException {
        long baseMillis = Math.min(5_000L, 100L << Math.min(attempt - 1, 5));
        long delayMillis = baseMillis + ThreadLocalRandom.current().nextLong(Math.max(1L, baseMillis / 4L));
        long deadline = System.nanoTime() + Duration.ofMillis(delayMillis).toNanos();
        try {
            while (!cancellationRequested.getAsBoolean() && System.nanoTime() < deadline) {
                long remainingMillis = Math.max(1L,
                        Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                Thread.sleep(Math.min(remainingMillis, 100L));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Step retry backoff was interrupted", exception);
        }
    }

    private StepRun executeStep(ClaimedTask task, ClaimedStep step, int attempt,
                                java.util.function.BooleanSupplier cancellationRequested,
                                Map<String, Object> stepOutputs, boolean enforceTaskDeadline) throws IOException {
        Path workingDirectory = safeWorkDirectory(task, step, attempt);
        if (isAdaptation(task)) {
            // 受保护步骤必须在写入通用 context/lease 文件之前分流，脚本只得到受限 UDS。
            if (adaptationHost == null) throw new ReaderAdaptationException(ErrorCode.DISABLED, 0);
            var execution = adaptationHost.execute(task, step, safeEntrypoint(step), workingDirectory, cancellationRequested);
            String code = execution.result().errorCode() == null ? null : execution.result().errorCode().code();
            return new StepRun(execution.result().status(), execution.process(), Map.of(), code, code, false);
        }
        Path contextFile = workingDirectory.resolve("task-context.json");
        Path resultFile = workingDirectory.resolve("task-result.json");
        Path errorFile = workingDirectory.resolve("task-error.json");
        Path leaseTokenFile = workingDirectory.resolve("task-lease-token");
        Files.createDirectories(workingDirectory);
        writeContext(task, step, attempt, stepOutputs, contextFile);
        writeLeaseToken(task, leaseTokenFile);
        Path entrypoint = safeEntrypoint(step);
        List<String> command = buildCommand(entrypoint, step.argumentsTemplate(), task.parameters());
        Map<String, String> environment = new LinkedHashMap<>();
        if (properties.scriptEnvironments() != null) {
            environment.putAll(properties.scriptEnvironments().getOrDefault(step.scriptPackage(), Map.of()));
        }
        // Executor 保留变量覆盖节点配置，避免 Secret 配置伪造任务身份或结果路径。
        environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        environment.put("LANG", "C.UTF-8");
        environment.put("PYTHONPATH", properties.pythonSdkRoot().toAbsolutePath().normalize().toString());
        environment.put("TASK_API_URL", normalizedSchedulerUrl());
        environment.put("TASK_EXECUTION_ID", task.executionId().toString());
        environment.put("TASK_FENCING_TOKEN", Long.toString(task.fencingToken()));
        // 子任务必须绑定到当前实际执行节点，避免依赖带点号标签的配置映射结果。
        environment.put("TASK_EXECUTOR_NODE_AFFINITY", properties.nodeName());
        environment.put("TASK_CONTEXT_FILE", contextFile.toString());
        environment.put("TASK_RESULT_FILE", resultFile.toString());
        environment.put("TASK_ERROR_FILE", errorFile.toString());
        environment.put("TASK_LEASE_TOKEN_FILE", leaseTokenFile.toString());
        environment.put("TASK_WORK_DIR", workingDirectory.toString());
        Duration timeout = Duration.ofSeconds(step.timeoutSeconds());
        if (enforceTaskDeadline) {
            Duration remaining = Duration.between(Instant.now(), task.deadlineAt());
            if (remaining.isNegative() || remaining.isZero()) {
                ScriptExecutionResult expired = new ScriptExecutionResult(
                        -1, "", "Task deadline exceeded", Duration.ZERO, true, false);
                return new StepRun("TIMED_OUT", expired, Map.of(), "TASK_TIMEOUT", "Task deadline exceeded", true);
            }
            if (remaining.compareTo(timeout) < 0) {
                timeout = remaining;
            }
        }
        ScriptExecutionResult result = processRunner.run(new ScriptExecutionRequest(
                command, step.scriptPackage(), workingDirectory, environment, timeout, cancellationRequested));
        Map<String, Object> outputs = readResult(resultFile);
        if (result.cancelled()) {
            return new StepRun("CANCELLED", result, outputs, "TASK_CANCELLED", limitedError(result.standardError()),
                    false);
        }
        if (result.timedOut()) {
            return new StepRun("TIMED_OUT", result, outputs, "STEP_TIMEOUT", limitedError(result.standardError()),
                    true);
        }
        if (result.exitCode() != 0) {
            TaskErrorDocument taskError = readTaskError(errorFile);
            if (taskError != null) {
                return new StepRun("FAILED", result, outputs, taskError.code(),
                        taskError.message() == null ? limitedError(result.standardError()) : taskError.message(),
                        taskError.retryable());
            }
            return new StepRun("FAILED", result, outputs, "SCRIPT_EXIT_NON_ZERO",
                    limitedError(result.standardError()), true);
        }
        return new StepRun("SUCCEEDED", result, outputs, null, null, false);
    }

    private Thread startLeaseMonitor(ClaimedTask task, AtomicBoolean cancellationRequested,
                                     AtomicBoolean stopped, AtomicReference<Instant> leaseUntil) {
        // 租约续期必须独立于脚本执行线程，避免阻塞型子进程占满虚拟线程调度资源时丢失续租。
        return Thread.ofPlatform().name("task-lease-monitor-" + task.executionId()).start(() -> {
            Instant firstFailureAt = null;
            while (!stopped.get()) {
                try {
                    // 执行心跳同时承载取消信号，不能只按租约续期下限等待。
                    long monitorSeconds = Math.max(1,
                            Math.min(properties.heartbeatSeconds(), Math.max(1, properties.leaseSeconds() / 3)));
                    Thread.sleep(Duration.ofSeconds(monitorSeconds));
                    if (!stopped.get()) {
                        var renewed = schedulerClient.heartbeatExecution(task);
                        leaseUntil.set(renewed.leaseUntil());
                        firstFailureAt = null;
                        if (renewed.cancelRequested() || !"ACTIVE".equals(renewed.leaseState())) {
                            cancellationRequested.set(true);
                            schedulerClient.releaseWorkloadAuthorization(task.executionId());
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException exception) {
                    Instant now = Instant.now();
                    if (firstFailureAt == null) {
                        firstFailureAt = now;
                    }
                    if (!now.isBefore(firstFailureAt.plusSeconds(properties.leaseSafetySeconds()))
                            || !now.isBefore(leaseUntil.get())) {
                        cancellationRequested.set(true);
                        schedulerClient.releaseWorkloadAuthorization(task.executionId());
                    }
                    LOGGER.warn("Execution lease heartbeat failed: executionId={}, error={}",
                            task.executionId(), exception.getMessage());
                }
            }
        });
    }

    private List<ClaimedStep> stepsOfKind(ClaimedTask task, String kind) {
        return task.steps().stream().filter(step -> kind.equals(step.stepKind()))
                .sorted(Comparator.comparingInt(ClaimedStep::sequenceNumber)).toList();
    }

    private Path safeWorkDirectory(ClaimedTask task, ClaimedStep step, int attempt) {
        Path root = properties.workRoot().toAbsolutePath().normalize();
        Path target = root.resolve(task.taskInstanceId().toString()).resolve(task.executionId().toString())
                .resolve(step.name()).resolve(Integer.toString(attempt)).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Task work directory escapes configured root");
        }
        return target;
    }

    private Path safeEntrypoint(ClaimedStep step) {
        Path root = properties.scriptRoot().toAbsolutePath().normalize();
        Path entrypoint = root.resolve(step.scriptPackage()).resolve(step.scriptVersion())
                .resolve(step.entrypoint()).normalize();
        if (!entrypoint.startsWith(root) || !Files.isRegularFile(entrypoint)) {
            throw new IllegalArgumentException("Script entrypoint is outside published script root or missing");
        }
        releaseVerifier.verifyEntrypoint(step.scriptPackage(), step.scriptVersion(), step.entrypoint(), entrypoint,
                step.scriptReleaseDigest());
        return entrypoint;
    }

    private static boolean isAdaptation(ClaimedTask task) { return "reader_adapt_novel_chapter".equalsIgnoreCase(task.taskName()); }

    private List<String> buildCommand(Path entrypoint, List<String> templates, Map<String, Object> parameters) {
        List<String> command = new ArrayList<>();
        String name = entrypoint.getFileName().toString();
        if (name.endsWith(".py")) {
            command.add(properties.pythonExecutable().toAbsolutePath().normalize().toString());
            Path runner = properties.pythonSdkRoot().toAbsolutePath().normalize()
                    .resolve("mytools_task_sdk/runner.py");
            if (Files.isRegularFile(runner)) {
                // 统一包装器只接收路径数组参数，不经过 shell 插值，并自动生成稳定错误分类文档。
                command.add("-m");
                command.add("mytools_task_sdk.runner");
            }
        } else if (name.endsWith(".sh")) {
            command.add("/bin/sh");
        }
        command.add(entrypoint.toString());
        for (String template : templates) {
            command.add(resolveArgument(template, parameters));
        }
        return command;
    }

    private String resolveArgument(String template, Map<String, Object> parameters) {
        String resolved = template;
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                continue;
            }
            resolved = resolved.replace("${parameters." + entry.getKey() + "}", String.valueOf(value));
        }
        if (resolved.contains("${parameters.")) {
            throw new IllegalArgumentException("Script argument contains an unresolved parameter");
        }
        return resolved;
    }

    private void writeContext(ClaimedTask task, ClaimedStep step, int attempt, Map<String, Object> stepOutputs,
                              Path contextFile) throws IOException {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("taskInstanceId", task.taskInstanceId());
        context.put("executionId", task.executionId());
        context.put("fencingToken", task.fencingToken());
        context.put("parentTaskInstanceId", task.parentTaskInstanceId());
        context.put("taskName", task.taskName());
        context.put("taskDeadlineAt", task.deadlineAt().toString());
        context.put("stepDefinitionId", step.stepDefinitionId());
        context.put("stepName", step.name());
        context.put("attempt", attempt);
        context.put("parameters", task.parameters());
        context.put("stepOutputs", stepOutputs);
        Files.writeString(contextFile, objectMapper.writeValueAsString(context), StandardCharsets.UTF_8);
    }

    private void writeLeaseToken(ClaimedTask task, Path tokenFile) throws IOException {
        Files.writeString(tokenFile, task.leaseToken().toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tokenFile, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统由节点部署权限负责保护工作目录。
        }
    }

    private String normalizedSchedulerUrl() {
        String value = properties.schedulerUrl();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private Map<String, Object> readResult(Path resultFile) throws IOException {
        if (!Files.isRegularFile(resultFile)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(Files.readString(resultFile, StandardCharsets.UTF_8), MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IOException("Task result file is not valid JSON", exception);
        }
    }

    private TaskErrorDocument readTaskError(Path errorFile) {
        if (!Files.isRegularFile(errorFile)) {
            return null;
        }
        try {
            TaskErrorDocument document = objectMapper.readValue(
                    Files.readString(errorFile, StandardCharsets.UTF_8), TaskErrorDocument.class);
            if (document.valid()) {
                return document;
            }
        } catch (IOException | RuntimeException ignored) {
            // 非法错误文档不能让脚本选择任意重试语义，按永久协议错误处理。
        }
        return new TaskErrorDocument("TASK_ERROR_DOCUMENT_INVALID", TaskErrorDocument.Category.PERMANENT,
                false, "Task error document is invalid");
    }

    private String limitedError(String value) {
        if (value == null || value.length() <= 2048) {
            return value;
        }
        return value.substring(0, 2048);
    }

    private record StepRun(String status, ScriptExecutionResult result, Map<String, Object> outputs,
                           String errorCode, String errorMessage, boolean retryable) {
    }

    private record StepOutcome(String status, ClaimedStep step, StepRun run, Map<String, Object> stepOutputs) {
    }

    private record RunningTask(int depth, boolean mayCreateChildren) {
    }

    private record CompensationOutcome(String status, boolean required, String errorCode) {

        private static CompensationOutcome notRequired() {
            return new CompensationOutcome("NOT_REQUIRED", false, null);
        }
    }
}
