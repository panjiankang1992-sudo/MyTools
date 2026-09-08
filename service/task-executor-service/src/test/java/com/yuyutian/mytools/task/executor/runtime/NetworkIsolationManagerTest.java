package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorNetworkIsolationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkIsolationManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldLeaveCommandUnchangedWhenDisabled() throws Exception {
        NetworkIsolationManager manager = new NetworkIsolationManager(
                new ExecutorNetworkIsolationProperties());

        assertEquals(List.of("/bin/echo", "ok"), manager.wrap("sample", List.of("/bin/echo", "ok")));
        manager.validate();
    }

    @Test
    void shouldWrapOpaqueArgumentsWithoutInterpolation() throws Exception {
        Path bubblewrap = executable("bwrap");
        NetworkIsolationManager manager = new NetworkIsolationManager(
                new ExecutorNetworkIsolationProperties(true, bubblewrap, Map.of()));
        String opaqueArgument = "$(touch should-not-run); value with spaces";

        List<String> wrapped = manager.wrap("offline-package", List.of("/bin/echo", opaqueArgument));

        assertEquals(bubblewrap.toString(), wrapped.getFirst());
        assertTrue(wrapped.contains("--unshare-net"));
        assertEquals(List.of("/bin/echo", opaqueArgument), wrapped.subList(wrapped.size() - 2, wrapped.size()));
        assertFalse(String.join(" ", wrapped.subList(0, wrapped.size() - 2)).contains(opaqueArgument));
        assertFalse(Files.exists(temporaryDirectory.resolve("should-not-run")));
    }

    @Test
    void shouldFailClosedWhenBubblewrapIsUnavailable() {
        NetworkIsolationManager manager = new NetworkIsolationManager(
                new ExecutorNetworkIsolationProperties(true, temporaryDirectory.resolve("missing"), Map.of()));

        assertThrows(IOException.class, () -> manager.wrap("offline-package", List.of("/bin/true")));
        assertThrows(IOException.class, manager::validate);
    }

    @Test
    void shouldRejectDeclaredDomainsUntilGatewayIsConfigured() throws Exception {
        Path bubblewrap = executable("bwrap");
        NetworkIsolationManager manager = new NetworkIsolationManager(
                new ExecutorNetworkIsolationProperties(true, bubblewrap,
                        Map.of("network-package", Set.of("api.example.test"))));

        assertThrows(IOException.class, () -> manager.wrap("network-package", List.of("/bin/true")));
        assertThrows(IOException.class, manager::validate);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void shouldRemoveExternalRoutesInBubblewrapNamespaceOnLinux() throws Exception {
        Path bubblewrap = Path.of(System.getenv().getOrDefault(
                "TASK_EXECUTOR_TEST_BUBBLEWRAP", "/usr/bin/bwrap"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(bubblewrap) && Files.isExecutable(bubblewrap));
        NetworkIsolationManager manager = new NetworkIsolationManager(
                new ExecutorNetworkIsolationProperties(true, bubblewrap, Map.of()));
        try {
            manager.validate();
        } catch (IOException exception) {
            org.junit.jupiter.api.Assumptions.abort("bubblewrap network namespace is unavailable: "
                    + exception.getMessage());
        }
        ScriptProcessRunner runner = new ScriptProcessRunner(
                new com.yuyutian.mytools.task.executor.config.ExecutorResourceLimitProperties(0, 0, 0),
                null, new CgroupV2Manager(new com.yuyutian.mytools.task.executor.config.ExecutorCgroupProperties()),
                manager);
        ScriptExecutionRequest request = new ScriptExecutionRequest(
                List.of("/bin/sh", "-c", "test \"$(wc -l < /proc/net/route)\" -eq 1"),
                "offline-package", temporaryDirectory, Map.of("PATH", "/usr/bin:/bin"),
                Duration.ofSeconds(5), () -> false);

        assertEquals(0, runner.run(request).exitCode());
    }

    private Path executable(String name) throws IOException {
        Path path = Files.createFile(temporaryDirectory.resolve(name));
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }
}
