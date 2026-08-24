package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptReleaseVerifierTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldVerifyIndexedEntrypointAndRejectRuntimeMutation() throws Exception {
        Path script = writeRelease(true);
        ExecutorProperties properties = properties(true);
        ScriptReleaseVerifier verifier = new ScriptReleaseVerifier(properties, new ObjectMapper());

        assertDoesNotThrow(() -> verifier.verifyEntrypoint("sample", "1.0.0", "scripts/main.py", script));
        Files.writeString(script, "print('changed')\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> verifier.verifyEntrypoint("sample", "1.0.0", "scripts/main.py", script));
    }

    @Test
    void shouldRejectMissingRequiredIndexAndAllowLegacyMode() {
        assertThrows(IllegalStateException.class,
                () -> new ScriptReleaseVerifier(properties(true), new ObjectMapper()));
        assertDoesNotThrow(() -> new ScriptReleaseVerifier(properties(false), new ObjectMapper()));
    }

    @Test
    void shouldRejectChangedFileDuringStartupVerification() throws Exception {
        Path script = writeRelease(false);
        Files.writeString(script, "print('changed')\n", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
                () -> new ScriptReleaseVerifier(properties(true), new ObjectMapper()));
    }

    @Test
    void shouldRejectUnsafeIndexedPackageIdentity() throws Exception {
        writeRelease(true);
        Path index = temporaryDirectory.resolve("scripts/package-index.json");
        String content = Files.readString(index, StandardCharsets.UTF_8)
                .replace("\"name\":\"sample\"", "\"name\":\"..\"");
        Files.writeString(index, content, StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
                () -> new ScriptReleaseVerifier(properties(true), new ObjectMapper()));
    }

    private Path writeRelease(boolean valid) throws Exception {
        Path script = temporaryDirectory.resolve("scripts/sample/1.0.0/scripts/main.py");
        Files.createDirectories(script.getParent());
        byte[] content = "print('ok')\n".getBytes(StandardCharsets.UTF_8);
        Files.write(script, content);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String indexedDigest = valid ? digest : "0".repeat(64);
        String index = """
                {"packageCount":1,"contentSha256":"ignored","packages":[{
                  "name":"sample","version":"1.0.0","entrypoint":"scripts/main.py","files":[
                    {"path":"scripts/main.py","sizeBytes":%d,"sha256":"%s"}]}]}
                """.formatted(content.length, indexedDigest);
        Files.writeString(temporaryDirectory.resolve("scripts/package-index.json"), index,
                StandardCharsets.UTF_8);
        return script;
    }

    private ExecutorProperties properties(boolean required) {
        return new ExecutorProperties("executor-test", "http://127.0.0.1:23210",
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("scripts"),
                temporaryDirectory.resolve("sdk"), 10, 1, 60, 2, Map.of(), Map.of(), Set.of(), required,
                Map.of());
    }
}
