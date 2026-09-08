package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.config.ExecutorLogArchiveProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将本地执行日志幂等归档到 Storage Gateway。
 */
@Component
public class ExecutionLogArchiver implements HealthIndicator {
    private final ExecutorLogArchiveProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    /**
     * 创建执行日志归档器。
     *
     * @param properties 归档配置
     * @param objectMapper JSON 映射器
     */
    @Autowired
    public ExecutionLogArchiver(ExecutorLogArchiveProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newHttpClient());
    }

    ExecutionLogArchiver(ExecutorLogArchiveProperties properties, ObjectMapper objectMapper,
                         HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 返回是否强制执行远端日志归档。
     *
     * @return 是否启用
     */
    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * 幂等归档一个执行目录中的日志分段与索引。
     *
     * @param task 已领取任务
     * @param workDirectory 执行工作目录
     * @throws IOException 读取或上传失败
     */
    public void archive(ClaimedTask task, Path workDirectory) throws IOException {
        if (!enabled()) {
            return;
        }
        try {
            archiveFiles(task, workDirectory);
            lastFailure.set(null);
        } catch (IOException exception) {
            lastFailure.set(exception.getMessage());
            throw exception;
        }
    }

    /**
     * 返回不包含令牌和路径载荷的归档健康状态。
     *
     * @return 健康状态
     */
    @Override
    public Health health() {
        String failure = lastFailure.get();
        if (failure != null) {
            return Health.outOfService().withDetail("error", "EXECUTOR_LOG_ARCHIVE_FAILED").build();
        }
        return Health.up().withDetail("enabled", enabled()).build();
    }

    private void archiveFiles(ClaimedTask task, Path workDirectory) throws IOException {
        if (!Files.isDirectory(workDirectory)) {
            throw new IOException("Execution work directory is unavailable for log archive");
        }
        List<Path> files;
        try (var stream = Files.walk(workDirectory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("process-log-index.json")
                            || path.getFileName().toString().matches("(stdout|stderr)-\\d{6}\\.log"))
                    .sorted().toList();
        }
        for (Path file : files) {
            upload(task, workDirectory, file);
        }
    }

    private void upload(ClaimedTask task, Path workDirectory, Path file) throws IOException {
        String relativeFile = workDirectory.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
        String digest = sha256(file);
        String key = "executor-log:" + task.executionId() + ":" + sha256(relativeFile) + ":" + digest;
        String relativePath = normalizedPrefix() + "/" + task.taskInstanceId() + "/"
                + task.executionId() + "/" + relativeFile;
        byte[] createBody = objectMapper.writeValueAsBytes(Map.of(
                "rootName", properties.rootName(), "relativePath", relativePath,
                "expectedSize", Files.size(file), "expectedSha256", digest, "idempotencyKey", key));
        HttpRequest create = request("/api/internal/v1/storage/uploads")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(createBody)).build();
        JsonNode upload = json(send(create, "create log archive upload"));
        String status = upload.path("status").asText();
        if ("SUCCEEDED".equals(status)) {
            return;
        }
        String uploadId = upload.path("id").asText();
        if (uploadId.isBlank()) {
            throw new IOException("Storage Gateway omitted log archive upload id");
        }
        HttpRequest content = request("/api/internal/v1/storage/uploads/" + uploadId + "/content")
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofFile(file)).build();
        JsonNode completed = json(send(content, "upload execution log"));
        if (!"SUCCEEDED".equals(completed.path("status").asText())
                || !digest.equals(completed.path("sha256").asText())) {
            throw new IOException("Storage Gateway did not confirm execution log archive");
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(properties.storageGatewayUrl().replaceAll("/+$", "") + path))
                .header("Authorization", "Bearer " + properties.internalToken());
    }

    private HttpResponse<byte[]> send(HttpRequest request, String action) throws IOException {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Storage Gateway failed to " + action + ": HTTP " + response.statusCode());
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while archiving execution log", exception);
        }
    }

    private JsonNode json(HttpResponse<byte[]> response) throws IOException {
        return objectMapper.readTree(response.body());
    }

    private String normalizedPrefix() {
        return properties.relativePathPrefix().replaceAll("^/+|/+$", "");
    }

    private String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private String sha256(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }
}
