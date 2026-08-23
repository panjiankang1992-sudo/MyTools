package com.yuyutian.mytools.task.executor.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    }
}
