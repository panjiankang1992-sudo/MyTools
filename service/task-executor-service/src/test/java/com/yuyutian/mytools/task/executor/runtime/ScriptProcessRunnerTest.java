package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorResourceLimitProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptProcessRunnerTest {

    @TempDir
    Path workDirectory;

    @Test
    void shouldExecuteCommandWithoutShellInterpolation() throws Exception {
        ScriptProcessRunner runner = new ScriptProcessRunner();
        ScriptExecutionRequest request = new ScriptExecutionRequest(
                List.of("/bin/sh", "-c", "printf task-ok"), workDirectory, Map.of("PATH", "/usr/bin:/bin"), Duration.ofSeconds(5)
        );

        ScriptExecutionResult result = runner.run(request);

        assertEquals(0, result.exitCode());
        assertEquals("task-ok", result.standardOutput());
        assertFalse(result.timedOut());
        assertFalse(result.cancelled());
    }

    @Test
    void shouldTerminateProcessWhenCancellationIsRequested() throws Exception {
        ScriptProcessRunner runner = new ScriptProcessRunner();
        ScriptExecutionRequest request = new ScriptExecutionRequest(
                List.of("/bin/sh", "-c", "sleep 10"), workDirectory, Map.of("PATH", "/usr/bin:/bin"),
                Duration.ofSeconds(20), () -> true
        );

        ScriptExecutionResult result = runner.run(request);

        assertFalse(result.timedOut());
        org.junit.jupiter.api.Assertions.assertTrue(result.cancelled());
    }

    @Test
    void shouldTerminateDescendantProcessesWhenCancellationIsRequested() throws Exception {
        ScriptProcessRunner runner = new ScriptProcessRunner();
        AtomicBoolean cancelled = new AtomicBoolean();
        Path childPidFile = workDirectory.resolve("child.pid");
        ScriptExecutionRequest request = new ScriptExecutionRequest(
                List.of("/bin/sh", "-c", "sleep 30 & echo $! > child.pid; wait"), workDirectory,
                Map.of("PATH", "/usr/bin:/bin"), Duration.ofSeconds(40), cancelled::get);

        var execution = Thread.startVirtualThread(() -> {
            try {
                runner.run(request);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        long waitDeadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!Files.isRegularFile(childPidFile) && System.nanoTime() < waitDeadline) {
            Thread.sleep(20);
        }
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        cancelled.set(true);
        execution.join(Duration.ofSeconds(8));

        assertFalse(execution.isAlive());
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void shouldStartTaskInIndependentLinuxSession() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ScriptProcessRunner.supportsProcessGroupIsolation());
        ScriptProcessRunner runner = new ScriptProcessRunner();
        ScriptExecutionRequest request = new ScriptExecutionRequest(
                List.of("/bin/sh", "-c", "printf '%s %s' \"$$\" \"$(ps -o sid= -p $$ | tr -d ' ')\""),
                workDirectory, Map.of("PATH", "/usr/bin:/bin"), Duration.ofSeconds(5));

        ScriptExecutionResult result = runner.run(request);

        String[] identity = result.standardOutput().split(" ");
        assertEquals(2, identity.length);
        assertEquals(identity[0], identity[1]);
    }

    @Test
    void shouldComposeResourceLimitsWithoutShellInterpolation() throws Exception {
        Path prlimit = workDirectory.resolve("prlimit");
        ScriptProcessRunner runner = new ScriptProcessRunner(
                new ExecutorResourceLimitProperties(7, 8, 9), prlimit);
        String opaqueArgument = "$(touch should-not-run)";

        List<String> command = runner.preparedCommand(List.of("/bin/echo", opaqueArgument));

        int index = command.indexOf(prlimit.toString());
        assertTrue(index >= 0);
        assertEquals(List.of(prlimit.toString(), "--cpu=7:7", "--as=8:8", "--fsize=9:9", "--",
                "/bin/echo", opaqueArgument), command.subList(index, command.size()));
        assertFalse(Files.exists(workDirectory.resolve("should-not-run")));
    }

    @Test
    void shouldFailClosedWhenResourceLimitsAreEnabledWithoutPrlimit() {
        ScriptProcessRunner runner = new ScriptProcessRunner(
                new ExecutorResourceLimitProperties(1, 0, 0), (Path) null);

        assertThrows(java.io.IOException.class, () -> runner.preparedCommand(List.of("/bin/true")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void shouldEnforceFileSizeLimitOnLinux() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ScriptProcessRunner.supportsResourceLimits());
        ScriptProcessRunner runner = new ScriptProcessRunner(new ExecutorResourceLimitProperties(0, 0, 1024));
        ScriptExecutionRequest request = new ScriptExecutionRequest(
                List.of("/usr/bin/python3", "-c", "open('limited.bin','wb').write(b'x'*4096)"),
                workDirectory, Map.of("PATH", "/usr/bin:/bin"), Duration.ofSeconds(5));

        ScriptExecutionResult result = runner.run(request);

        assertNotEquals(0, result.exitCode());
        assertTrue(Files.size(workDirectory.resolve("limited.bin")) <= 1024);
    }

    @Test
    void shouldSegmentLargeOutputAndWriteDigestIndex() throws Exception {
        ScriptProcessRunner runner = new ScriptProcessRunner();
        int outputBytes = ScriptProcessRunner.SEGMENT_BYTES + 17;
        ScriptExecutionRequest request = new ScriptExecutionRequest(
                List.of("/usr/bin/python3", "-c", "import sys;sys.stdout.write('x'*" + outputBytes + ")"),
                workDirectory, Map.of("PATH", "/usr/bin:/bin"), Duration.ofSeconds(10));

        ScriptExecutionResult result = runner.run(request);

        assertEquals(0, result.exitCode());
        assertEquals(ScriptProcessRunner.PREVIEW_BYTES, result.standardOutput().length());
        assertEquals(2, result.logIndex().stdout().size());
        assertEquals(ScriptProcessRunner.SEGMENT_BYTES, result.logIndex().stdout().getFirst().sizeBytes());
        assertEquals(17, result.logIndex().stdout().get(1).sizeBytes());
        assertEquals(64, result.logIndex().stdout().getFirst().sha256().length());
        assertTrue(Files.isRegularFile(workDirectory.resolve("process-log-index.json")));
        assertTrue(Files.size(workDirectory.resolve("stdout-000001.log")) <= ScriptProcessRunner.SEGMENT_BYTES);
    }
}
