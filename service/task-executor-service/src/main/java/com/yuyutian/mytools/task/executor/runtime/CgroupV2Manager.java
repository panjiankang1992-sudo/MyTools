package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorCgroupProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;

/**
 * 为单次任务进程树创建和回收 cgroup v2。
 */
@Component
public class CgroupV2Manager {

    private static final long CPU_PERIOD_MICROS = 100_000L;
    private static final String JOIN_SCRIPT = """
            printf '%s\\n' "$$" > "$1" || exit 125
            shift
            exec "$@"
            """;
    private final ExecutorCgroupProperties properties;
    private final Path shellPath;

    /**
     * 创建 cgroup v2 管理器。
     *
     * @param properties cgroup v2 配置
     */
    @Autowired
    public CgroupV2Manager(ExecutorCgroupProperties properties) {
        this(properties, Path.of("/bin/sh"));
    }

    CgroupV2Manager(ExecutorCgroupProperties properties, Path shellPath) {
        this.properties = properties;
        this.shellPath = shellPath;
    }

    /**
     * 创建本次执行的 cgroup，并返回安全参数化命令包装器。
     *
     * @return cgroup 租约
     * @throws IOException 平台、委派或限制配置不满足要求
     */
    public CgroupLease prepare() throws IOException {
        if (!properties.enabled()) {
            return CgroupLease.disabled();
        }
        requireLinuxCgroupV2();
        if (!Files.isRegularFile(shellPath) || !Files.isExecutable(shellPath)) {
            throw new IOException("Executor cgroup v2 requires an executable /bin/sh launcher");
        }
        Path root = properties.root().toAbsolutePath().normalize();
        Files.createDirectories(root);
        requireControllers(root);
        Path group = root.resolve("task-" + UUID.randomUUID());
        Files.createDirectory(group);
        boolean prepared = false;
        try {
            writeLimits(group);
            prepared = true;
            return new CgroupLease(group, shellPath);
        } finally {
            if (!prepared) {
                Files.deleteIfExists(group);
            }
        }
    }

    /**
     * 校验当前 cgroup v2 委派和限制文件是否可用。
     *
     * @throws IOException 配置启用但无法创建受限任务 cgroup
     */
    public void validate() throws IOException {
        try (CgroupLease ignored = prepare()) {
            // 创建、写入限制并删除探测 cgroup，确保注册节点前已具备实际执行能力。
        }
    }

    private void requireLinuxCgroupV2() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
                || !Files.isRegularFile(Path.of("/sys/fs/cgroup/cgroup.controllers"))) {
            throw new IOException("Executor cgroup v2 limits require Linux unified cgroup v2");
        }
    }

    private void requireControllers(Path root) throws IOException {
        Path controllersPath = root.resolve("cgroup.controllers");
        if (!Files.isRegularFile(controllersPath)) {
            throw new IOException("Executor cgroup root is not a delegated cgroup v2 directory");
        }
        Set<String> controllers = Set.of(Files.readString(controllersPath).trim().split("\\s+"));
        if (properties.maximumMemoryBytes() > 0 && !controllers.contains("memory")) {
            throw new IOException("Executor cgroup root does not delegate the memory controller");
        }
        if (properties.maximumProcesses() > 0 && !controllers.contains("pids")) {
            throw new IOException("Executor cgroup root does not delegate the pids controller");
        }
        if (properties.maximumCpuPercent() > 0 && !controllers.contains("cpu")) {
            throw new IOException("Executor cgroup root does not delegate the cpu controller");
        }
    }

    private void writeLimits(Path group) throws IOException {
        if (properties.maximumMemoryBytes() > 0) {
            Files.writeString(group.resolve("memory.max"), Long.toString(properties.maximumMemoryBytes()));
        }
        if (properties.maximumProcesses() > 0) {
            Files.writeString(group.resolve("pids.max"), Integer.toString(properties.maximumProcesses()));
        }
        if (properties.maximumCpuPercent() > 0) {
            long quota = Math.max(1L, CPU_PERIOD_MICROS * properties.maximumCpuPercent() / 100L);
            Files.writeString(group.resolve("cpu.max"), quota + " " + CPU_PERIOD_MICROS);
        }
    }

    /**
     * 单次任务 cgroup 生命周期租约。
     */
    public static final class CgroupLease implements AutoCloseable {

        private final Path group;
        private final Path shellPath;

        private CgroupLease(Path group, Path shellPath) {
            this.group = group;
            this.shellPath = shellPath;
        }

        private static CgroupLease disabled() {
            return new CgroupLease(null, null);
        }

        static CgroupLease forTesting(Path group, Path shellPath) {
            return new CgroupLease(group, shellPath);
        }

        /**
         * 使用固定启动脚本包装命令，原始参数仅通过位置参数传递。
         *
         * @param command 原始命令
         * @return 安全包装后的命令
         */
        public List<String> wrap(List<String> command) {
            if (group == null) {
                return List.copyOf(command);
            }
            List<String> wrapped = new ArrayList<>(command.size() + 5);
            wrapped.add(shellPath.toString());
            wrapped.add("-c");
            wrapped.add(JOIN_SCRIPT);
            wrapped.add("mytools-cgroup-launcher");
            wrapped.add(group.resolve("cgroup.procs").toString());
            wrapped.addAll(command);
            return List.copyOf(wrapped);
        }

        /**
         * 回收已结束任务的空 cgroup。
         *
         * @throws IOException cgroup 仍包含进程或目录无法删除
         */
        @Override
        public void close() throws IOException {
            if (group != null) {
                terminateRemainingProcesses();
                Files.deleteIfExists(group);
            }
        }

        private void terminateRemainingProcesses() throws IOException {
            Path processes = group.resolve("cgroup.procs");
            if (!Files.exists(processes) || Files.readString(processes).isBlank()) {
                return;
            }
            Path kill = group.resolve("cgroup.kill");
            if (Files.exists(kill)) {
                Files.writeString(kill, "1");
            } else {
                // 较旧内核没有 cgroup.kill 时，按当前成员 PID 强制终止并在循环中重新读取。
                terminateListedProcesses(processes);
            }
            Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
            while (Instant.now().isBefore(deadline) && !Files.readString(processes).isBlank()) {
                if (!Files.exists(kill)) {
                    terminateListedProcesses(processes);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while draining task cgroup", exception);
                }
            }
            if (!Files.readString(processes).isBlank()) {
                throw new IOException("Task cgroup still contains processes after forced termination");
            }
        }

        private void terminateListedProcesses(Path processes) throws IOException {
            for (String value : Files.readAllLines(processes)) {
                if (!value.isBlank()) {
                    try {
                        ProcessHandle.of(Long.parseLong(value.trim())).ifPresent(ProcessHandle::destroyForcibly);
                    } catch (NumberFormatException exception) {
                        throw new IOException("Task cgroup contains an invalid process identifier", exception);
                    }
                }
            }
        }
    }
}
