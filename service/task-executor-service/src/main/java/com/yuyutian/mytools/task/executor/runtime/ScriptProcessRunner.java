package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.config.ExecutorCgroupProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorNetworkIsolationProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorResourceLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用独立进程执行已解析命令，并分段保存进程输出。
 */
@Component
public class ScriptProcessRunner {

    static final int SEGMENT_BYTES = 8 * 1024 * 1024;
    static final int PREVIEW_BYTES = 1024 * 1024;
    private static final Logger LOGGER = LoggerFactory.getLogger(ScriptProcessRunner.class);
    private static final Path SETSID_PATH = findSetsid();
    private static final Path KILL_PATH = findKill();
    private static final Path PRLIMIT_PATH = findPrlimit();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorResourceLimitProperties resourceLimits;
    private final Path prlimitPath;
    private final CgroupV2Manager cgroupV2Manager;
    private final NetworkIsolationManager networkIsolationManager;

    /**
     * 创建未启用资源限制的兼容运行器。
     */
    public ScriptProcessRunner() {
        this(new ExecutorResourceLimitProperties(0, 0, 0), PRLIMIT_PATH,
                new CgroupV2Manager(new ExecutorCgroupProperties()),
                new NetworkIsolationManager(new ExecutorNetworkIsolationProperties()));
    }

    /**
     * 创建带资源限制的脚本运行器。
     *
     * @param resourceLimits 子进程资源限制
     */
    public ScriptProcessRunner(ExecutorResourceLimitProperties resourceLimits) {
        this(resourceLimits, PRLIMIT_PATH, new CgroupV2Manager(new ExecutorCgroupProperties()),
                new NetworkIsolationManager(new ExecutorNetworkIsolationProperties()));
    }

    /**
     * 创建带单进程及任务级聚合资源限制的脚本运行器。
     *
     * @param resourceLimits 子进程资源限制
     * @param cgroupV2Manager cgroup v2 管理器
     */
    @Autowired
    public ScriptProcessRunner(ExecutorResourceLimitProperties resourceLimits,
                               CgroupV2Manager cgroupV2Manager,
                               NetworkIsolationManager networkIsolationManager) {
        this(resourceLimits, PRLIMIT_PATH, cgroupV2Manager, networkIsolationManager);
    }

    ScriptProcessRunner(ExecutorResourceLimitProperties resourceLimits, Path prlimitPath) {
        this(resourceLimits, prlimitPath, new CgroupV2Manager(new ExecutorCgroupProperties()),
                new NetworkIsolationManager(new ExecutorNetworkIsolationProperties()));
    }

    ScriptProcessRunner(ExecutorResourceLimitProperties resourceLimits, Path prlimitPath,
                        CgroupV2Manager cgroupV2Manager) {
        this(resourceLimits, prlimitPath, cgroupV2Manager,
                new NetworkIsolationManager(new ExecutorNetworkIsolationProperties()));
    }

    ScriptProcessRunner(ExecutorResourceLimitProperties resourceLimits, Path prlimitPath,
                        CgroupV2Manager cgroupV2Manager, NetworkIsolationManager networkIsolationManager) {
        this.resourceLimits = resourceLimits;
        this.prlimitPath = prlimitPath;
        this.cgroupV2Manager = cgroupV2Manager;
        this.networkIsolationManager = networkIsolationManager;
    }

    /**
     * 执行脚本进程并收集有限预览及完整分段索引。
     *
     * @param request 执行请求
     * @return 执行结果
     * @throws IOException 进程或文件操作失败
     */
    public ScriptExecutionResult run(ScriptExecutionRequest request) throws IOException {
        try (CgroupV2Manager.CgroupLease cgroupLease = cgroupV2Manager.prepare()) {
            return runInCgroup(request, cgroupLease);
        }
    }

    private ScriptExecutionResult runInCgroup(ScriptExecutionRequest request,
                                              CgroupV2Manager.CgroupLease cgroupLease) throws IOException {
        Files.createDirectories(request.workingDirectory());
        List<String> networkIsolated = networkIsolationManager.wrap(request.scriptPackage(), request.command());
        LaunchCommand launchCommand = isolateCommand(cgroupLease.wrap(networkIsolated));
        ProcessBuilder builder = new ProcessBuilder(launchCommand.command());
        builder.directory(request.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(new HashMap<>(request.environment()));
        Instant startedAt = Instant.now();
        Process process = builder.start();
        AtomicReference<IOException> streamFailure = new AtomicReference<>();
        StreamCapture stdout = new StreamCapture(request.workingDirectory(), "stdout");
        StreamCapture stderr = new StreamCapture(request.workingDirectory(), "stderr");
        Thread stdoutPump = pump(process.getInputStream(), stdout, streamFailure);
        Thread stderrPump = pump(process.getErrorStream(), stderr, streamFailure);
        boolean finished = false;
        boolean cancelled = false;
        boolean timedOut = false;
        try {
            Instant deadline = startedAt.plus(request.timeout());
            while (!finished) {
                finished = process.waitFor(250, TimeUnit.MILLISECONDS);
                if (!finished && request.cancellationRequested().getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                if (!finished && Instant.now().isAfter(deadline)) {
                    timedOut = true;
                    break;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminateProcess(process, launchCommand.processGroupIsolated(), Duration.ZERO);
            throw new IOException("Script execution was interrupted", exception);
        }
        if (!finished) {
            terminateProcess(process, launchCommand.processGroupIsolated(), Duration.ofSeconds(5));
        }
        joinPump(stdoutPump);
        joinPump(stderrPump);
        if (streamFailure.get() != null) {
            throw streamFailure.get();
        }
        ProcessLogIndex index = new ProcessLogIndex(stdout.segments(), stderr.segments());
        objectMapper.writeValue(request.workingDirectory().resolve("process-log-index.json").toFile(), index);
        int exitCode = finished ? process.exitValue() : -1;
        return new ScriptExecutionResult(exitCode, stdout.preview(), stderr.preview(),
                Duration.between(startedAt, Instant.now()), timedOut, cancelled, index);
    }

    private Thread pump(InputStream input, StreamCapture capture, AtomicReference<IOException> failure) {
        return Thread.startVirtualThread(() -> {
            try (input; capture) {
                input.transferTo(capture);
            } catch (IOException exception) {
                failure.compareAndSet(null, exception);
            }
        });
    }

    private void joinPump(Thread thread) throws IOException {
        try {
            thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while finalizing process logs", exception);
        }
    }

    private void terminateProcess(Process process, boolean processGroupIsolated, Duration gracePeriod) {
        if (processGroupIsolated && signalProcessGroup(process.pid(), "TERM")) {
            waitForProcessGroup(process.pid(), gracePeriod);
            if (isProcessGroupAlive(process.pid())) {
                signalProcessGroup(process.pid(), "KILL");
            }
            terminateProcessTree(process, Duration.ZERO);
            return;
        }
        // 即使进程组信号失败或主进程已退出，也遍历仍可见的后代完成兼容性兜底。
        terminateProcessTree(process, gracePeriod);
    }

    private void terminateProcessTree(Process process, Duration gracePeriod) {
        // 先对子进程发送终止信号，避免父进程退出后孙进程失去可追踪关系。
        var descendants = process.descendants().toList().reversed();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        Instant deadline = Instant.now().plus(gracePeriod);
        while (Instant.now().isBefore(deadline)
                && (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void waitForProcessGroup(long processGroupId, Duration gracePeriod) {
        Instant deadline = Instant.now().plus(gracePeriod);
        while (Instant.now().isBefore(deadline) && isProcessGroupAlive(processGroupId)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isProcessGroupAlive(long processGroupId) {
        return signalProcessGroup(processGroupId, "0");
    }

    private boolean signalProcessGroup(long processGroupId, String signal) {
        if (KILL_PATH == null) {
            return false;
        }
        try {
            Process signalProcess = new ProcessBuilder(KILL_PATH.toString(), "-" + signal,
                    "--", "-" + processGroupId).start();
            boolean finished = signalProcess.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                signalProcess.destroyForcibly();
                return false;
            }
            return signalProcess.exitValue() == 0;
        } catch (IOException exception) {
            LOGGER.warn("Failed to signal isolated process group: pgid={}, signal={}", processGroupId, signal,
                    exception);
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean supportsProcessGroupIsolation() {
        return SETSID_PATH != null && KILL_PATH != null;
    }

    List<String> preparedCommand(List<String> command) throws IOException {
        return isolateCommand(command).command();
    }

    private LaunchCommand isolateCommand(List<String> command) throws IOException {
        List<String> constrained = applyResourceLimits(command);
        if (!supportsProcessGroupIsolation()) {
            return new LaunchCommand(constrained, false);
        }
        List<String> isolated = new ArrayList<>(constrained.size() + 1);
        isolated.add(SETSID_PATH.toString());
        isolated.addAll(constrained);
        return new LaunchCommand(List.copyOf(isolated), true);
    }

    private List<String> applyResourceLimits(List<String> command) throws IOException {
        if (!resourceLimits.enabled()) {
            return List.copyOf(command);
        }
        if (prlimitPath == null) {
            throw new IOException("Executor resource limits require Linux prlimit");
        }
        List<String> constrained = new ArrayList<>(command.size() + 5);
        constrained.add(prlimitPath.toString());
        if (resourceLimits.maximumCpuSeconds() > 0) {
            constrained.add("--cpu=" + resourceLimits.maximumCpuSeconds() + ":"
                    + resourceLimits.maximumCpuSeconds());
        }
        if (resourceLimits.maximumVirtualMemoryBytes() > 0) {
            constrained.add("--as=" + resourceLimits.maximumVirtualMemoryBytes() + ":"
                    + resourceLimits.maximumVirtualMemoryBytes());
        }
        if (resourceLimits.maximumFileBytes() > 0) {
            constrained.add("--fsize=" + resourceLimits.maximumFileBytes() + ":"
                    + resourceLimits.maximumFileBytes());
        }
        constrained.add("--");
        constrained.addAll(command);
        return List.copyOf(constrained);
    }

    private static Path findSetsid() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return null;
        }
        return findExecutable(Path.of("/usr/bin/setsid"), Path.of("/bin/setsid"));
    }

    private static Path findKill() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return null;
        }
        return findExecutable(Path.of("/bin/kill"), Path.of("/usr/bin/kill"));
    }

    private static Path findPrlimit() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return null;
        }
        return findExecutable(Path.of("/usr/bin/prlimit"), Path.of("/bin/prlimit"));
    }

    static boolean supportsResourceLimits() {
        return PRLIMIT_PATH != null;
    }

    private static Path findExecutable(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private record LaunchCommand(List<String> command, boolean processGroupIsolated) {
    }

    private static final class StreamCapture extends OutputStream implements AutoCloseable {
        private final Path directory;
        private final String streamName;
        private final ByteArrayOutputStream preview = new ByteArrayOutputStream(PREVIEW_BYTES);
        private final List<ProcessLogIndex.LogSegment> segments = new ArrayList<>();
        private OutputStream output;
        private MessageDigest digest;
        private Path currentPath;
        private long currentSize;
        private int sequence;

        private StreamCapture(Path directory, String streamName) {
            this.directory = directory;
            this.streamName = streamName;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            int cursor = offset;
            int remaining = length;
            while (remaining > 0) {
                ensureSegment();
                int chunk = (int) Math.min(remaining, SEGMENT_BYTES - currentSize);
                output.write(data, cursor, chunk);
                digest.update(data, cursor, chunk);
                if (preview.size() < PREVIEW_BYTES) {
                    preview.write(data, cursor, Math.min(chunk, PREVIEW_BYTES - preview.size()));
                }
                cursor += chunk;
                remaining -= chunk;
                currentSize += chunk;
                if (currentSize == SEGMENT_BYTES) {
                    closeSegment();
                }
            }
        }

        @Override
        public void close() throws IOException {
            closeSegment();
        }

        private void ensureSegment() throws IOException {
            if (output != null) {
                return;
            }
            sequence++;
            currentPath = directory.resolve("%s-%06d.log".formatted(streamName, sequence));
            output = Files.newOutputStream(currentPath);
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IOException("SHA-256 is unavailable", exception);
            }
            currentSize = 0;
        }

        private void closeSegment() throws IOException {
            if (output == null) {
                return;
            }
            output.close();
            segments.add(new ProcessLogIndex.LogSegment(currentPath.getFileName().toString(), currentSize,
                    HexFormat.of().formatHex(digest.digest())));
            output = null;
        }

        private String preview() {
            return preview.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        private List<ProcessLogIndex.LogSegment> segments() {
            return List.copyOf(segments);
        }
    }
}
