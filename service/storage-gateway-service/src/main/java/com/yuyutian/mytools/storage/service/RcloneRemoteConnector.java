package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.RemoteJobView;
import com.yuyutian.mytools.storage.model.RemoteContent;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 仅允许回环 rclone RC 白名单操作的远端连接器。
 */
@Component
public class RcloneRemoteConnector implements ProviderObjectConnector {
    private static final Pattern REMOTE_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String configuredUrl;
    private final String user;
    private final String password;
    private final String configPath;
    private final String httpProxy;
    private URI baseUri;

    /**
     * 创建受控 rclone 连接器。
     *
     * @param objectMapper JSON 映射器
     * @param configuredUrl rclone RC 地址
     * @param user RC 用户名
     * @param password RC 密码
     */
    public RcloneRemoteConnector(ObjectMapper objectMapper,
                                 @Value("${storage.rclone-rc-url:http://127.0.0.1:5572}") String configuredUrl,
                                 @Value("${storage.rclone-rc-user:}") String user,
                                 @Value("${storage.rclone-rc-password:}") String password,
                                 @Value("${storage.rclone-config-path:/opt/yuyutian/mytools/config/rclone.conf}") String configPath,
                                 @Value("${storage.rclone-http-proxy:http://127.0.0.1:7893}") String httpProxy) {
        this.objectMapper = objectMapper;
        this.configuredUrl = configuredUrl;
        this.user = user;
        this.password = password;
        this.configPath = configPath;
        this.httpProxy = httpProxy;
    }

    /**
     * 验证 RC 地址不得离开本机信任边界。
     */
    @PostConstruct
    public void validateConfiguration() {
        URI candidate = URI.create(configuredUrl);
        String host = candidate.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (!"http".equals(candidate.getScheme()) || !loopback || candidate.getUserInfo() != null
                || candidate.getQuery() != null || candidate.getFragment() != null
                || !(candidate.getPath().isEmpty() || "/".equals(candidate.getPath()))) {
            throw new IllegalStateException("rclone RC must use a loopback HTTP endpoint");
        }
        baseUri = URI.create(configuredUrl.endsWith("/") ? configuredUrl : configuredUrl + "/");
    }

    /**
     * 列出服务端 Provider 配置的单级目录。
     *
     * @param remoteKey 服务端 remote 键
     * @param path Provider 内相对路径
     * @return 标准化对象列表
     */
    public List<RemoteObjectView> list(String remoteKey, String path) {
        if (!REMOTE_KEY.matcher(remoteKey).matches()) {
            throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code());
        }
        String safePath = validPath(path, true);
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(Map.of("fs", remoteKey + ":", "remote", safePath,
                    "opt", Map.of("recurse", false, "showHash", true)));
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve("operations/list"))
                    .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
            if (!user.isBlank() || !password.isBlank()) {
                builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body().length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            JsonNode values = objectMapper.readTree(response.body()).path("list");
            if (!values.isArray() || values.size() > 10000) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            List<RemoteObjectView> result = new ArrayList<>();
            for (JsonNode value : values) {
                result.add(normalize(value));
            }
            return List.copyOf(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    /**
     * 返回 rclone Provider 类型。
     *
     * @return 固定类型
     */
    @Override
    public String providerType() {
        return "RCLONE";
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsContentRead() {
        return true;
    }

    /**
     * 使用 Provider 内部 remote 键列目录。
     *
     * @param provider Provider 配置
     * @param path 相对路径
     * @return 标准化对象列表
     */
    @Override
    public List<RemoteObjectView> list(com.yuyutian.mytools.storage.model.StorageProvider provider, String path) {
        return list(provider.remoteKey(), path);
    }

    /**
     * 启动白名单中的跨 Provider 树复制或同步。
     *
     * @param operationType 操作类型
     * @param sourceRemoteKey 来源 remote 键
     * @param sourcePath 来源路径
     * @param targetRemoteKey 目标 remote 键
     * @param targetPath 目标路径
     * @return rclone 后台任务标识
     */
    public long startTransfer(String operationType, String sourceRemoteKey, String sourcePath,
                              String targetRemoteKey, String targetPath) {
        if (!REMOTE_KEY.matcher(sourceRemoteKey).matches() || !REMOTE_KEY.matcher(targetRemoteKey).matches()) {
            throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code());
        }
        String action = switch (operationType) {
            case "COPY_TREE" -> "sync/copy";
            case "SYNC_REMOTE" -> "sync/sync";
            default -> throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
        };
        JsonNode response = call(action, Map.of(
                "srcFs", remotePath(sourceRemoteKey, sourcePath),
                "dstFs", remotePath(targetRemoteKey, targetPath),
                "_async", true), Duration.ofSeconds(30));
        long jobId = response.path("jobid").asLong(0);
        if (jobId <= 0) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        return jobId;
    }

    /**
     * 查询受控 rclone 后台任务状态。
     *
     * @param jobId rclone 任务标识
     * @return 标准状态
     */
    public RemoteJobView jobStatus(long jobId) {
        return jobStatus(jobId, false);
    }

    /**
     * 查询复制校验后台任务，并同时要求校验输出成功。
     *
     * @param jobId rclone 任务标识
     * @return 标准状态
     */
    public RemoteJobView verificationJobStatus(long jobId) {
        return jobStatus(jobId, true);
    }

    /**
     * 检查受控远端路径是否存在。
     *
     * @param remoteKey remote 键
     * @param path 相对路径
     * @return 是否存在
     */
    public boolean exists(String remoteKey, String path) {
        requireRemoteKey(remoteKey);
        JsonNode response = call("operations/stat", Map.of(
                "fs", remoteKey + ":", "remote", validPath(path, false)), Duration.ofSeconds(30));
        return !response.path("item").isMissingNode() && !response.path("item").isNull();
    }

    /**
     * 启动逐字节下载校验任务。
     *
     * @param sourceRemoteKey 来源 remote 键
     * @param sourcePath 来源路径
     * @param targetRemoteKey 目标 remote 键
     * @param targetPath 目标路径
     * @return 后台任务标识
     */
    public long startVerification(String sourceRemoteKey, String sourcePath,
                                  String targetRemoteKey, String targetPath) {
        return startAsync("operations/check", Map.of(
                "srcFs", remotePath(sourceRemoteKey, sourcePath),
                "dstFs", remotePath(targetRemoteKey, targetPath),
                "download", true));
    }

    /**
     * 启动目录清理任务。
     *
     * @param remoteKey remote 键
     * @param path 非空相对路径
     * @return 后台任务标识
     */
    public long startPurge(String remoteKey, String path) {
        requireRemoteKey(remoteKey);
        return startAsync("operations/purge", Map.of(
                "fs", remoteKey + ":", "remote", validPath(path, false)));
    }

    private RemoteJobView jobStatus(long jobId, boolean requireOutputSuccess) {
        if (jobId <= 0) {
            throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        JsonNode response = call("job/status", Map.of("jobid", jobId), Duration.ofSeconds(30));
        boolean finished = response.path("finished").asBoolean(false);
        boolean success = finished && response.path("success").asBoolean(false)
                && (!requireOutputSuccess || response.path("output").path("success").asBoolean(false));
        String errorCode = finished && !success ? ErrorCode.REMOTE_FAILURE.code() : null;
        return new RemoteJobView(jobId, finished, success, errorCode);
    }

    private long startAsync(String action, Map<String, Object> payload) {
        Map<String, Object> request = new java.util.LinkedHashMap<>(payload);
        request.put("_async", true);
        long jobId = call(action, request, Duration.ofSeconds(30)).path("jobid").asLong(0);
        if (jobId <= 0) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        return jobId;
    }

    private String remotePath(String remoteKey, String path) {
        requireRemoteKey(remoteKey);
        String safePath = validPath(path, true);
        return remoteKey + ":" + safePath;
    }

    private void requireRemoteKey(String remoteKey) {
        if (!REMOTE_KEY.matcher(remoteKey).matches()) {
            throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code());
        }
    }

    /**
     * 请求停止受控 rclone 后台任务。
     *
     * @param jobId rclone 任务标识
     */
    public void stopJob(long jobId) {
        if (jobId > 0) {
            call("job/stop", Map.of("jobid", jobId), Duration.ofSeconds(30));
        }
    }

    /**
     * 流式打开一个 Provider 内的普通文件。
     *
     * @param remoteKey remote 键
     * @param path 相对文件路径
     * @param maximumBytes 最大允许字节数
     * @return 有硬上限的响应流
     */
    public RemoteContent openContent(String remoteKey, String path, long maximumBytes) {
        requireRemoteKey(remoteKey);
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_TOO_LARGE.code());
        }
        try {
            String target = remoteKey + ":" + validPath(path, false);
            byte[] requestBody = objectMapper.writeValueAsBytes(Map.of(
                    "command", "cat", "arg", List.of(target),
                    "opt", Map.of("config", configPath, "http-proxy", httpProxy),
                    "returnType", "STREAM_ONLY_STDOUT"));
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve("core/command"))
                    .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
            if (!user.isBlank() || !password.isBlank()) {
                builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<InputStream> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maximumBytes) {
                response.body().close();
                throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_TOO_LARGE.code());
            }
            return new RemoteContent(new MaximumInputStream(response.body(), maximumBytes), declared);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    /** 使用 Provider 内部 remote 键打开一个文件。 @param provider Provider @param path 路径 @param maximumBytes 上限 @return 内容 */
    @Override
    public RemoteContent openContent(com.yuyutian.mytools.storage.model.StorageProvider provider,
                                     String path, long maximumBytes) {
        return openContent(provider.remoteKey(), path, maximumBytes);
    }

    /** 在读取超过声明上限时立即关闭上游连接。 */
    private static final class MaximumInputStream extends FilterInputStream {
        private final long maximum;
        private long count;

        private MaximumInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(long amount) throws IOException {
            count += amount;
            if (count > maximum) {
                close();
                throw new IOException(ErrorCode.REMOTE_CONTENT_TOO_LARGE.code());
            }
        }
    }

    private JsonNode call(String action, Map<String, Object> payload, Duration timeout) {
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(action))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
            if (!user.isBlank() || !password.isBlank()) {
                builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body().length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            JsonNode document = objectMapper.readTree(response.body());
            if (!document.isObject()) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            return document;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private RemoteObjectView normalize(JsonNode value) {
        String path = validPath(value.path("Path").asText(), false);
        String name = value.path("Name").asText("").trim();
        if (name.isBlank() || name.length() > 512 || name.contains("/") || name.contains("\\")) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        Instant modifiedAt = null;
        try {
            if (value.hasNonNull("ModTime")) {
                modifiedAt = OffsetDateTime.parse(value.path("ModTime").asText()).toInstant();
            }
        } catch (DateTimeParseException ignored) {
            // 远端时间缺失不阻断目录读取。
        }
        String digest = value.path("Hashes").path("SHA-256").asText(null);
        if (digest != null && !digest.matches("^[a-fA-F0-9]{64}$")) {
            digest = null;
        }
        return new RemoteObjectView(path, name, value.path("IsDir").asBoolean(),
                Math.max(0, value.path("Size").asLong()), modifiedAt, digest);
    }

    private String validPath(String value, boolean allowEmpty) {
        return RemotePathValidator.validate(value, allowEmpty);
    }
}
