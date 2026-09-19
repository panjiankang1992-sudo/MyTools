package com.yuyutian.mytools.task.executor.runtime.adaptation;

import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutorWorkloadTls;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelAdaptationRun;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelAdaptationWorkflow;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderClient;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels;
import com.yuyutian.mytools.task.executor.client.adaptation.ReaderAdaptationClient;
import com.yuyutian.mytools.task.executor.client.adaptation.ReaderAdaptationException;
import com.yuyutian.mytools.task.executor.client.adaptation.ReaderSettlementClient;
import com.yuyutian.mytools.task.executor.client.adaptation.ReaderSettlementRelay;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import com.yuyutian.mytools.task.executor.config.ExecutorNovelAdaptationProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorWorkloadTlsProperties;
import com.yuyutian.mytools.task.executor.runtime.ScriptExecutionRequest;
import com.yuyutian.mytools.task.executor.runtime.ScriptExecutionResult;
import com.yuyutian.mytools.task.executor.runtime.ScriptProcessRunner;
import com.yuyutian.mytools.task.executor.runtime.ScriptReleaseVerifier;
import com.yuyutian.mytools.task.executor.runtime.WorkloadAuthorizationRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 专属改编执行宿主；通用任务不共享此节点，脚本只能通过隔离 UDS 请求一次宿主运行。 */
@Component
public final class NovelAdaptationTaskHost implements AutoCloseable {
    private static final String TASK = "reader_adapt_novel_chapter";
    private final ExecutorNovelAdaptationProperties properties;
    private final ExecutorWorkloadTls tls;
    private final WorkloadAuthorizationRegistry authorizations;
    private final ScriptProcessRunner processes;
    private final ScriptReleaseVerifier releases;
    private final Map<UUID, NovelAdaptationWorkflow> active = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(2);
    private final AtomicBoolean closing = new AtomicBoolean();
    private ReaderSettlementRelay relay;
    private ReaderSettlementClient settlement;
    private NovelProviderClient provider;
    private NovelAdaptationSandbox sandbox;
    private URI reader;

    /** 按独立开关装配恢复与创建；未启用时不读取任何新增文件，也不访问网络。 */
    public NovelAdaptationTaskHost(ExecutorNovelAdaptationProperties properties, ExecutorProperties executor,
            ExecutorWorkloadTls tls, ExecutorWorkloadTlsProperties tlsProperties, WorkloadAuthorizationRegistry authorizations,
            ScriptProcessRunner processes, ScriptReleaseVerifier releases) {
        this.properties = properties; this.tls = tls; this.authorizations = authorizations; this.processes = processes; this.releases = releases;
        if (!properties.recoveryEnabled()) return;
        try {
            // 挂载宿主秘密的节点不得执行通用脚本，即使只保留恢复功能也必须是专属集群。
            if (tls.thumbprint() == null || !Set.of("reader-adaptation").equals(executor.clusterNames())) throw disabled();
            reader = URI.create(properties.readerUrl());
            settlement = new ReaderSettlementClient(reader, tls);
            relay = new ReaderSettlementRelay(Path.of(properties.relayRoot()), Path.of(properties.relayKeyFile()));
            if (properties.enabled()) {
                if (!executor.requirePackageIndex() || !executor.requireNonRoot() || executor.labels() == null
                        || !"enabled".equals(executor.labels().get("reader.adaptation"))) throw disabled();
                sandbox = new NovelAdaptationSandbox(Path.of(properties.sandboxExecutable()), Path.of(properties.sandboxRoot()),
                        List.of(Path.of(properties.providerCredentialFile()), Path.of(properties.relayKeyFile()), Path.of(properties.relayRoot()),
                                Path.of(tlsProperties.keyStoreFile()), Path.of(tlsProperties.keyStorePasswordFile()),
                                Path.of(tlsProperties.trustStoreFile()), Path.of(tlsProperties.trustStorePasswordFile())));
                sandbox.validate();
                provider = new NovelProviderClient(new NovelProviderModels.Deployment(properties.providerDeploymentId(), properties.modelId(),
                        properties.credentialGeneration(), properties.maximumRequestBytes(), properties.maximumOutputTokens(), properties.acceptSse()),
                        Path.of(properties.providerCredentialFile()), properties.providerHeader(), properties.providerPrefix(),
                        properties.providerContentCompatibility(), properties.providerQuoteProtocol(), properties.providerInsertionProtocol());
            }
        } catch (RuntimeException exception) { close(); throw disabled(); }
    }

    /** 专属节点即使关闭创建也拒绝通用脚本访问宿主凭据。 */
    public boolean dedicated() { return properties.recoveryEnabled(); }

    /** 校验完整不可变单步执行契约，不能靠空步骤或补偿步骤跳过宿主约束。 */
    public void validate(ClaimedTask task) {
        if (closing.get() || !properties.enabled() || provider == null) throw disabled();
        validateTaskContract(task);
    }

    static void validateTaskContract(ClaimedTask task) {
        if (task == null || !TASK.equals(task.taskName())
                || task.steps() == null || task.steps().size() != 1 || task.parentTaskInstanceId() != null || task.mayCreateChildren()
                || task.parameters() == null || !task.parameters().keySet().equals(Set.of("adaptationId"))) throw disabled();
        ClaimedStep step = task.steps().getFirst();
        if (step == null || !TASK.equals(step.scriptPackage()) || !"1.0.0".equals(step.scriptVersion()) || !"scripts/main.py".equals(step.entrypoint())
                || !"run".equals(step.name()) || !"NORMAL".equals(step.stepKind()) || step.maxAttempts() != 1 || step.sequenceNumber() != 1
                || step.timeoutSeconds() != 900 || !"FAIL_TASK".equals(step.failurePolicy()) || step.argumentsTemplate() == null
                || !step.argumentsTemplate().isEmpty() || step.scriptReleaseDigest() == null || !step.scriptReleaseDigest().matches("[0-9a-f]{64}")) throw disabled();
    }

    /** 只把固定元数据和进程日志索引交回通用上报链，正文从不进入 Scheduler。 */
    public record Execution(NovelAdaptationRun.Result result, ScriptExecutionResult process) { }

    /** 在独立心跳已经启动的执行线程中运行受限包；不创建通用 task-context 或 lease-token 文件。 */
    public Execution execute(ClaimedTask task, ClaimedStep step, Path entrypoint, Path workDirectory, BooleanSupplier cancelled) {
        validate(task);
        if (!task.steps().getFirst().equals(step) || !slots.tryAcquire()) throw disabled();
        AtomicBoolean stopped = new AtomicBoolean(); NovelAdaptationWorkflow workflow = null;
        boolean registered = false;
        Instant started = Instant.now();
        try {
            relay.pendingCount();
            Path published = entrypoint.toAbsolutePath().normalize().getParent().getParent();
            releases.verifyEntrypoint(TASK, "1.0.0", "scripts/main.py", entrypoint, step.scriptReleaseDigest());
            BooleanSupplier permitted = () -> !closing.get() && !stopped.get() && !cancelled.getAsBoolean() && Instant.now().isBefore(task.deadlineAt());
            ReaderAdaptationClient client = new ReaderAdaptationClient(reader, task, tls, authorizations);
            workflow = new NovelAdaptationWorkflow(client, provider, relay, permitted);
            if (active.putIfAbsent(task.executionId(), workflow) != null) throw disabled();
            registered = true;
            Instant deadline = task.deadlineAt().isBefore(started.plusSeconds(900)) ? task.deadlineAt() : started.plusSeconds(900);
            NovelAdaptationRun run = new NovelAdaptationRun(client, workflow, deadline, permitted);
            try (var broker = new NovelAdaptationBroker(Path.of(properties.brokerRoot()), run::execute, permitted, deadline)) {
                Duration timeout = Duration.between(Instant.now(), deadline);
                if (timeout.isNegative() || timeout.isZero()) return failed("TIMED_OUT", ErrorCode.DEADLINE, started);
                var process = processes.run(new ScriptExecutionRequest(sandbox.command(published, broker.directory()), TASK, workDirectory,
                        Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"), timeout, () -> !permitted.getAsBoolean()));
                var outcome = broker.result().orElse(new NovelAdaptationRun.Result("FAILED", ErrorCode.CONTEXT));
                if (process.cancelled()) outcome = new NovelAdaptationRun.Result("CANCELLED", ErrorCode.FENCED);
                else if (process.timedOut()) outcome = new NovelAdaptationRun.Result("TIMED_OUT", ErrorCode.DEADLINE);
                else if (process.exitCode() != 0 && "SUCCEEDED".equals(outcome.status())) outcome = new NovelAdaptationRun.Result("FAILED", ErrorCode.PROTOCOL);
                return new Execution(outcome, process);
            }
        } catch (ReaderAdaptationException exception) { return failed("FAILED", exception.error(), started); }
        catch (Exception exception) { return failed("FAILED", ErrorCode.UNKNOWN, started); }
        finally {
            stopped.set(true);
            // 重复执行的拒绝分支不能撤销已经运行的同一执行授权。
            if (registered) authorizations.close(task.executionId());
            if (workflow != null) { workflow.close(); active.remove(task.executionId(), workflow); }
            slots.release();
        }
    }

    /** 创建关闭后仍周期恢复旧记录；固定错误不包含正文、能力或文件路径。 */
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void recover() {
        if (!properties.recoveryEnabled() || closing.get() || relay == null) return;
        try { relay.recoverOne(settlement); }
        catch (ReaderAdaptationException ignored) { /* 存储失败保持失败关闭；后续入口的容量检查同样拒绝新执行。 */ }
    }

    private static Execution failed(String status, ErrorCode code, Instant started) {
        return new Execution(new NovelAdaptationRun.Result(status, code), new ScriptExecutionResult(-1, "", "", Duration.between(started, Instant.now()),
                "TIMED_OUT".equals(status), "CANCELLED".equals(status)));
    }
    private static ReaderAdaptationException disabled() { return new ReaderAdaptationException(ErrorCode.DISABLED, 0); }

    /** 先撤销生成与关闭 workflow，再释放 Provider/relay；旧日志留待下一宿主恢复。 */
    @PreDestroy @Override public void close() {
        closing.set(true);
        active.forEach((id, workflow) -> { authorizations.close(id); workflow.close(); });
        active.clear();
        if (provider != null) provider.close();
        if (relay != null) relay.close();
    }
}
