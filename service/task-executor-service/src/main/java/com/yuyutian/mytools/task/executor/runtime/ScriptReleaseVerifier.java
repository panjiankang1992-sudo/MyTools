package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 不可变脚本发布索引和运行入口完整性验证器。
 */
@Component
public class ScriptReleaseVerifier {

    private final Path scriptRoot;
    private final Map<String, IndexedEntrypoint> entrypoints;
    private final boolean indexed;

    /**
     * 加载并验证脚本发布索引。
     *
     * @param properties Executor 配置
     * @param objectMapper JSON 映射器
     */
    public ScriptReleaseVerifier(ExecutorProperties properties, ObjectMapper objectMapper) {
        this.scriptRoot = properties.scriptRoot().toAbsolutePath().normalize();
        Path indexPath = scriptRoot.resolve("package-index.json");
        if (Files.isSymbolicLink(indexPath) || !Files.isRegularFile(indexPath)) {
            if (properties.requirePackageIndex()) {
                throw new IllegalStateException("Required script package index is missing");
            }
            this.entrypoints = Map.of();
            this.indexed = false;
            return;
        }
        try {
            this.entrypoints = loadAndVerify(indexPath, objectMapper);
            this.indexed = true;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Script package release verification failed", exception);
        }
    }

    /**
     * 在执行步骤前再次验证入口仍属于已发布索引且内容未改变。
     *
     * @param packageName 脚本包名称
     * @param version 脚本包版本
     * @param entrypoint 入口相对路径
     * @param path 已解析入口路径
     */
    public void verifyEntrypoint(String packageName, String version, String entrypoint, Path path) {
        if (!indexed) {
            return;
        }
        IndexedEntrypoint expected = entrypoints.get(packageName + "\0" + version);
        if (expected == null || !expected.path().equals(entrypoint)) {
            throw new IllegalArgumentException("Script entrypoint is not present in package release index");
        }
        verifyFile(path, expected.sizeBytes(), expected.sha256());
    }

    private Map<String, IndexedEntrypoint> loadAndVerify(Path indexPath, ObjectMapper objectMapper)
            throws IOException {
        JsonNode root = objectMapper.readTree(indexPath.toFile());
        JsonNode packages = root.path("packages");
        if (!packages.isArray() || root.path("packageCount").asInt(-1) != packages.size()) {
            throw new IllegalArgumentException("Script package index count is invalid");
        }
        Map<String, IndexedEntrypoint> loaded = new HashMap<>();
        for (JsonNode packageNode : packages) {
            String packageName = requiredText(packageNode, "name");
            String version = requiredText(packageNode, "version");
            String entrypoint = requiredText(packageNode, "entrypoint");
            if (!packageName.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                    || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("Script package index identity is invalid");
            }
            String key = packageName + "\0" + version;
            if (loaded.containsKey(key)) {
                throw new IllegalArgumentException("Script package index contains duplicate versions");
            }
            IndexedEntrypoint indexedEntrypoint = null;
            JsonNode files = packageNode.path("files");
            if (!files.isArray() || files.isEmpty()) {
                throw new IllegalArgumentException("Script package index contains no files");
            }
            for (JsonNode file : files) {
                String relativeValue = requiredText(file, "path");
                Path relative = Path.of(relativeValue);
                if (relative.isAbsolute() || relativeValue.contains("\\") || relative.normalize().startsWith("..")) {
                    throw new IllegalArgumentException("Script package index contains an unsafe file path");
                }
                long sizeBytes = file.path("sizeBytes").asLong(-1);
                String sha256 = requiredText(file, "sha256");
                Path actual = scriptRoot.resolve(packageName).resolve(version).resolve(relative).normalize();
                Path packageRoot = scriptRoot.resolve(packageName).resolve(version).normalize();
                if (!actual.startsWith(packageRoot)) {
                    throw new IllegalArgumentException("Script package file escapes package root");
                }
                verifyFile(actual, sizeBytes, sha256);
                if (relativeValue.equals(entrypoint)) {
                    indexedEntrypoint = new IndexedEntrypoint(entrypoint, sizeBytes, sha256);
                }
            }
            if (indexedEntrypoint == null) {
                throw new IllegalArgumentException("Script package entrypoint is not indexed");
            }
            loaded.put(key, indexedEntrypoint);
        }
        return Map.copyOf(loaded);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Script package index field is missing: " + field);
        }
        return value;
    }

    private void verifyFile(Path path, long expectedSize, String expectedSha256) {
        if (expectedSize < 0 || !expectedSha256.matches("[a-f0-9]{64}")
                || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Indexed script package file is invalid");
        }
        try {
            Path realRoot = scriptRoot.toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot) || Files.size(realPath) != expectedSize
                    || !digest(realPath).equals(expectedSha256)) {
                throw new IllegalArgumentException("Indexed script package file content changed");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Indexed script package file cannot be read", exception);
        }
    }

    private String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record IndexedEntrypoint(String path, long sizeBytes, String sha256) {
    }
}
