package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.node.ExecutorNodeAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ObjectMapper objectMapper;
    private final AtomicInteger runningTasks = new AtomicInteger();

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
        this.properties = properties;
        this.nodeAgent = nodeAgent;
        this.schedulerClient = schedulerClient;
        this.processRunner = processRunner;
        this.releaseVerifier = releaseVerifier;
        this.objectMapper = objectMapper;
    }

    /**
     * 在节点有剩余容量时领取一个任务。
     */
    @Scheduled(fixedDelayString = "${executor.poll-seconds:1}000", initialDelay = 1500)
    public void poll() {
        ExecutorNodeRegistration registration = nodeAgent.registration();
        if (registration == null || runningTasks.get() >= properties.maxConcurrentTasks()) {
            return;
        }
        try {
            Optional<ClaimedTask> claimed = schedulerClient.claim(registration.id(), nodeAgent.instanceId());
            if (claimed.isEmpty()) {
                return;
            }
            runningTasks.incrementAndGet();
            nodeAgent.setRunningTasks(runningTasks.get());
            Thread.startVirtualThread(() -> execute(claimed.get()));
        } catch (IOException exception) {
            LOGGER.warn("Task claim failed: {}", exception.getMessage());
        }
    }

    private void execute(ClaimedTask task) {
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        AtomicBoolean monitorStopped = new AtomicBoolean();
        Thread monitor = startLeaseMonitor(task, cancellationRequested, monitorStopped);
        String finalStatus = "SUCCEEDED";
        try {
            StepOutcome outcome = executeNormalSteps(task, cancellationRequested);
            finalStatus = outcome.status();
            executeScenarioSteps(task, outcome, cancellationRequested);
        } catch (Exception exception) {
            finalStatus = cancellationRequested.get() ? "CANCELLED" : "FAILED";
            LOGGER.error("Task execution failed: taskInstanceId={}", task.taskInstanceId(), exception);
        } finally {
            monitorStopped.set(true);
            monitor.interrupt();
            try {
                schedulerClient.complete(task, finalStatus);
            } catch (IOException exception) {
                LOGGER.error("Task completion report failed: taskInstanceId={}, status={}",
                        task.taskInstanceId(), finalStatus, exception);
            }
            runningTasks.decrementAndGet();
            nodeAgent.setRunningTasks(runningTasks.get());
        }
    }

    private StepOutcome executeNormalSteps(ClaimedTask task, AtomicBoolean cancellationRequested) throws IOException {
        List<ClaimedStep> normalSteps = stepsOfKind(task, "NORMAL");
        Map<String, Object> stepOutputs = new LinkedHashMap<>();
        for (ClaimedStep step : normalSteps) {
            if (cancellationRequested.get()) {
                return new StepOutcome("CANCELLED", step, null, stepOutputs);
            }
            StepRun run = executeWithRetry(task, step, cancellationRequested::get, stepOutputs, true);
            stepOutputs.put(step.name(), run.outputs());
            if (run.status().equals("TIMED_OUT")) {
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

    private void executeScenarioSteps(ClaimedTask task, StepOutcome outcome,
                                      AtomicBoolean cancellationRequested) throws IOException {
        String kind = switch (outcome.status()) {
            case "TIMED_OUT" -> "ON_TIMEOUT";
            case "CANCELLED" -> "ON_CANCEL";
            case "FAILED" -> "ON_FAILURE";
            default -> null;
        };
        if (kind == null) {
            return;
        }
        for (ClaimedStep step : stepsOfKind(task, kind)) {
            executeWithRetry(task, step, () -> false, outcome.stepOutputs(), false);
        }
    }

    private StepRun executeWithRetry(ClaimedTask task, ClaimedStep step,
                                     java.util.function.BooleanSupplier cancellationRequested,
                                     Map<String, Object> stepOutputs, boolean enforceTaskDeadline) throws IOException {
        StepRun last = null;
        for (int attempt = 1; attempt <= step.maxAttempts(); attempt++) {
            last = executeStep(task, step, attempt, cancellationRequested, stepOutputs, enforceTaskDeadline);
            schedulerClient.reportStep(task, step, attempt, last.status(), last.result().exitCode(),
                    last.outputs(), last.errorCode(), last.errorMessage());
            if (last.status().equals("SUCCEEDED") || last.status().equals("CANCELLED")
                    || last.status().equals("TIMED_OUT")) {
                return last;
            }
        }
        return last;
    }

    private StepRun executeStep(ClaimedTask task, ClaimedStep step, int attempt,
                                java.util.function.BooleanSupplier cancellationRequested,
                                Map<String, Object> stepOutputs, boolean enforceTaskDeadline) throws IOException {
        Path workingDirectory = safeWorkDirectory(task, step, attempt);
        Path contextFile = workingDirectory.resolve("task-context.json");
        Path resultFile = workingDirectory.resolve("task-result.json");
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
        Object nodeAffinity = properties.labels() == null ? null : properties.labels().get("executor.node");
        if (nodeAffinity != null) {
            environment.put("TASK_EXECUTOR_NODE_AFFINITY", String.valueOf(nodeAffinity));
        }
        environment.put("TASK_CONTEXT_FILE", contextFile.toString());
        environment.put("TASK_RESULT_FILE", resultFile.toString());
        environment.put("TASK_LEASE_TOKEN_FILE", leaseTokenFile.toString());
        environment.put("TASK_WORK_DIR", workingDirectory.toString());
        Duration timeout = Duration.ofSeconds(step.timeoutSeconds());
        if (enforceTaskDeadline) {
            Duration remaining = Duration.between(Instant.now(), task.deadlineAt());
            if (remaining.isNegative() || remaining.isZero()) {
                ScriptExecutionResult expired = new ScriptExecutionResult(
                        -1, "", "Task deadline exceeded", Duration.ZERO, true, false);
                return new StepRun("TIMED_OUT", expired, Map.of(), "TASK_TIMEOUT", "Task deadline exceeded");
            }
            if (remaining.compareTo(timeout) < 0) {
                timeout = remaining;
            }
        }
        ScriptExecutionResult result = processRunner.run(new ScriptExecutionRequest(
                command, workingDirectory, environment, timeout, cancellationRequested));
        Map<String, Object> outputs = readResult(resultFile);
        if (result.cancelled()) {
            return new StepRun("CANCELLED", result, outputs, "TASK_CANCELLED", limitedError(result.standardError()));
        }
        if (result.timedOut()) {
            return new StepRun("TIMED_OUT", result, outputs, "STEP_TIMEOUT", limitedError(result.standardError()));
        }
        if (result.exitCode() != 0) {
            return new StepRun("FAILED", result, outputs, "SCRIPT_EXIT_NON_ZERO", limitedError(result.standardError()));
        }
        return new StepRun("SUCCEEDED", result, outputs, null, null);
    }

    private Thread startLeaseMonitor(ClaimedTask task, AtomicBoolean cancellationRequested,
                                     AtomicBoolean stopped) {
        return Thread.startVirtualThread(() -> {
            while (!stopped.get()) {
                try {
                    // 执行心跳同时承载取消信号，不能只按租约续期下限等待。
                    long monitorSeconds = Math.max(1,
                            Math.min(properties.heartbeatSeconds(), Math.max(1, properties.leaseSeconds() / 3)));
                    Thread.sleep(Duration.ofSeconds(monitorSeconds));
                    if (!stopped.get() && schedulerClient.heartbeatExecution(task).cancelRequested()) {
                        cancellationRequested.set(true);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException exception) {
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
        releaseVerifier.verifyEntrypoint(step.scriptPackage(), step.scriptVersion(), step.entrypoint(), entrypoint);
        return entrypoint;
    }

    private List<String> buildCommand(Path entrypoint, List<String> templates, Map<String, Object> parameters) {
        List<String> command = new ArrayList<>();
        String name = entrypoint.getFileName().toString();
        if (name.endsWith(".py")) {
            command.add("python3");
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

    private String limitedError(String value) {
        if (value == null || value.length() <= 2048) {
            return value;
        }
        return value.substring(0, 2048);
    }

    private record StepRun(String status, ScriptExecutionResult result, Map<String, Object> outputs,
                           String errorCode, String errorMessage) {
    }

    private record StepOutcome(String status, ClaimedStep step, StepRun run, Map<String, Object> stepOutputs) {
    }
}
