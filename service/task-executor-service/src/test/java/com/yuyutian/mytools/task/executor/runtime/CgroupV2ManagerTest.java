package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorCgroupProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CgroupV2ManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldLeaveCommandUnchangedWhenDisabled() throws Exception {
        CgroupV2Manager manager = new CgroupV2Manager(new ExecutorCgroupProperties());

        try (CgroupV2Manager.CgroupLease lease = manager.prepare()) {
            assertEquals(List.of("/bin/echo", "ok"), lease.wrap(List.of("/bin/echo", "ok")));
        }
    }

    @Test
    void shouldWrapOpaqueArgumentsWithoutShellInterpolation() throws Exception {
        Path group = Files.createDirectory(temporaryDirectory.resolve("task-group"));
        String opaqueArgument = "$(touch should-not-run); value with spaces";

        try (CgroupV2Manager.CgroupLease lease = CgroupV2Manager.CgroupLease.forTesting(
                group, Path.of("/bin/sh"))) {
            List<String> wrapped = lease.wrap(List.of("/bin/echo", opaqueArgument));

            assertEquals("/bin/sh", wrapped.getFirst());
            assertEquals("-c", wrapped.get(1));
            assertFalse(wrapped.get(2).contains(opaqueArgument));
            assertEquals(List.of("/bin/echo", opaqueArgument), wrapped.subList(wrapped.size() - 2, wrapped.size()));
            assertTrue(wrapped.get(4).endsWith("/cgroup.procs"));
        }
        assertFalse(Files.exists(temporaryDirectory.resolve("should-not-run")));
        assertFalse(Files.exists(group));
    }

    @Test
    void shouldFailClosedWhenConfiguredRootIsNotDelegatedCgroupV2() {
        ExecutorCgroupProperties properties = new ExecutorCgroupProperties(
                true, temporaryDirectory.resolve("not-delegated"), 1024, 2, 100);
        CgroupV2Manager manager = new CgroupV2Manager(properties);

        assertThrows(IOException.class, manager::validate);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void shouldApplyDelegatedLimitsAndKillOrphanProcessOnLinux() throws Exception {
        String configuredRoot = System.getenv("TASK_EXECUTOR_TEST_CGROUP_ROOT");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                configuredRoot != null && !configuredRoot.isBlank(),
                "TASK_EXECUTOR_TEST_CGROUP_ROOT must point to a writable delegated cgroup v2 root");
        Path orphanPidFile = temporaryDirectory.resolve("orphan.pid");
        ExecutorCgroupProperties properties = new ExecutorCgroupProperties(
                true, Path.of(configuredRoot), 67_108_864L, 8, 150);
        CgroupV2Manager manager = new CgroupV2Manager(properties);
        ScriptProcessRunner runner = new ScriptProcessRunner(
                new com.yuyutian.mytools.task.executor.config.ExecutorResourceLimitProperties(0, 0, 0),
                null, manager);
        String probe = """
                group=$(awk -F: '$1 == "0" {print $3}' /proc/self/cgroup)
                printf '%s|%s|%s' "$(cat /sys/fs/cgroup${group}/memory.max)" \
                  "$(cat /sys/fs/cgroup${group}/pids.max)" "$(cat /sys/fs/cgroup${group}/cpu.max)"
                sleep 30 </dev/null >/dev/null 2>&1 &
                printf '%s' "$!" > orphan.pid
                """;
        ScriptExecutionRequest request = new ScriptExecutionRequest(
                List.of("/bin/sh", "-c", probe), temporaryDirectory,
                Map.of("PATH", "/usr/bin:/bin"), Duration.ofSeconds(5));

        ScriptExecutionResult result = runner.run(request);

        assertEquals(0, result.exitCode());
        assertEquals("67108864|8|150000 100000", result.standardOutput());
        long orphanPid = Long.parseLong(Files.readString(orphanPidFile));
        assertFalse(ProcessHandle.of(orphanPid).map(ProcessHandle::isAlive).orElse(false));
    }
}
