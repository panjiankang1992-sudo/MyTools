package com.yuyutian.mytools.task.executor.runtime.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.runtime.ScriptReleaseVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NovelAdaptationPackageTest {
    @TempDir Path temporary;

    @Test
    void realAssemblerPublishesAnIndexedPackageThatExecutorVerifies() throws Exception {
        Path service = serviceRoot();
        Path published = temporary.resolve("published");
        Path diagnostics = temporary.resolve("assembler.log");
        ProcessBuilder builder = new ProcessBuilder("python3", service.resolve("scripts/assemble_executor_packages.py").toString(),
                "--service-root", service.toString(), "--output", published.toString())
                .redirectErrorStream(true).redirectOutput(diagnostics.toFile());
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin:/usr/local/bin");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Package assembly timed out");
            assertEquals(0, process.exitValue());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        var properties = new ExecutorProperties("fixture", "http://127.0.0.1:23210", temporary.resolve("work"), published,
                temporary.resolve("sdk"), Path.of("/usr/bin/python3"), 10, 1, 60, 2,
                Map.of(), Map.of(), Set.of(), true, Map.of());
        var verifier = new ScriptReleaseVerifier(properties, new ObjectMapper());
        String digest = verifier.releaseDigests().get("reader_adapt_novel_chapter:1.0.0");
        assertNotNull(digest); assertTrue(digest.matches("[a-f0-9]{64}"));
        Path root = published.resolve("reader_adapt_novel_chapter/1.0.0");
        Path entry = root.resolve("scripts/main.py");
        assertDoesNotThrow(() -> verifier.verifyEntrypoint("reader_adapt_novel_chapter", "1.0.0", "scripts/main.py", entry, digest));
        assertFalse(Files.exists(root.resolve("tests"))); assertFalse(Files.exists(root.resolve("scripts/__pycache__")));
        assertEquals(Files.readString(service.resolve("reader-service/packages/reader_adapt_novel_chapter/1.0.0/scripts/main.py")), Files.readString(entry));
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyEntrypoint("reader_adapt_novel_chapter", "1.0.0", "scripts/main.py", entry, "0".repeat(64)));
        // 只在本测试临时发布副本中注入内容变化，原始包及任何既有发布均不修改。
        Files.writeString(entry, "raise SystemExit(1)\n");
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyEntrypoint("reader_adapt_novel_chapter", "1.0.0", "scripts/main.py", entry, digest));
    }

    private static Path serviceRoot() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("scripts/assemble_executor_packages.py"))) return current;
            if (Files.isRegularFile(current.resolve("service/scripts/assemble_executor_packages.py"))) return current.resolve("service");
        }
        throw new IllegalStateException("Service package assembler was not found");
    }
}
