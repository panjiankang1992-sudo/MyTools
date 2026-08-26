package com.yuyutian.mytools.pikpak.service;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;
import static com.yuyutian.mytools.pikpak.common.ErrorCode.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 仅开放 PikPak 必需 rclone RC 操作的回环连接器。 */
@Component
public class RclonePikPakClient {
    private static final Pattern REMOTE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String configuredUrl;
    private final String user;
    private final String password;
    private URI baseUri;

    /** 创建连接器。 @param mapper JSON 映射器 @param configuredUrl RC 地址 @param user 用户名 @param password 密码 */
    public RclonePikPakClient(ObjectMapper mapper,
        @Value("${pikpak.rclone-rc-url:http://127.0.0.1:5572}") String configuredUrl,
        @Value("${pikpak.rclone-rc-user:}") String user,
        @Value("${pikpak.rclone-rc-password:}") String password) {
        this.mapper = mapper;
        this.configuredUrl = configuredUrl;
        this.user = user;
        this.password = password;
    }

    /** 验证 RC 只能监听回环 HTTP。 */
    @PostConstruct
    public void validateConfiguration() {
        URI candidate = URI.create(configuredUrl);
        String host = candidate.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (!"http".equals(candidate.getScheme()) || !loopback || candidate.getUserInfo() != null
                || candidate.getQuery() != null || candidate.getFragment() != null
                || !(candidate.getPath().isEmpty() || "/".equals(candidate.getPath()))) {
            throw new IllegalStateException(RCLONE_ENDPOINT_INVALID.code());
        }
        baseUri = URI.create(configuredUrl.endsWith("/") ? configuredUrl : configuredUrl + "/");
    }

    /** 提交一次离线 URI。 @param remoteKey 远端键 @param path 隔离目录 @param magnetUri magnet URI */
    public void addUrl(String remoteKey, String path, String magnetUri) {
        call("backend/command", Map.of("command", "addurl", "fs", remotePath(remoteKey, path),
            "arg", List.of(magnetUri), "opt", Map.of()), Duration.ofMinutes(3));
    }

    /** 递归列出隔离目录文件。 @param remoteKey 远端键 @param path 路径 @return 文件 */
    public List<RemoteItem> list(String remoteKey, String path) {
        return listFiles(remoteKey, path, true, Duration.ofMinutes(2));
    }

    /** 按收件箱顶层批次列出文件，避免对整个收件箱执行一次无界递归。 @param remoteKey 远端键 @param path 路径 @param ignoredBatches 已基线批次 @return 文件 */
    public List<RemoteItem> listWatchRoot(String remoteKey, String path, Set<String> ignoredBatches) {
        JsonNode response = listResponse(remoteKey, path, false, Duration.ofSeconds(20));
        JsonNode values = response.path("list");
        validateList(values);
        List<RemoteItem> result = new ArrayList<>();
        for (JsonNode value : values) {
            String relative = relativePath(path, value.path("Path").asText());
            if (!value.path("IsDir").asBoolean(false)) {
                result.add(toRemoteItem(value, relative));
                continue;
            }
            if (ignoredBatches.contains(relative)) {
                continue;
            }
            // 每个顶层目录是独立批次，只递归展开该批次，避免历史目录拖慢全部扫描。
            for (RemoteItem item : listFiles(remoteKey, validPath(path) + "/" + relative, true,
                    Duration.ofMinutes(2))) {
                String itemPath = item.relativePath().startsWith(relative + "/")
                    ? item.relativePath() : relative + "/" + item.relativePath();
                result.add(new RemoteItem(item.remoteFileId(), itemPath, item.sizeBytes(), item.modifiedAt()));
            }
        }
        return List.copyOf(result);
    }

    private List<RemoteItem> listFiles(String remoteKey, String path, boolean recurse, Duration timeout) {
        JsonNode response = listResponse(remoteKey, path, recurse, timeout);
        JsonNode values = response.path("list");
        validateList(values);
        List<RemoteItem> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.path("IsDir").asBoolean(false)) {
                continue;
            }
            String relative = relativePath(path, value.path("Path").asText());
            result.add(toRemoteItem(value, relative));
        }
        return List.copyOf(result);
    }

    private JsonNode listResponse(String remoteKey, String path, boolean recurse, Duration timeout) {
        remotePath(remoteKey, path);
        return call("operations/list", Map.of("fs", remoteKey + ":", "remote", validPath(path),
            "opt", Map.of("recurse", recurse)), timeout);
    }

    private void validateList(JsonNode values) {
        if (!values.isArray() || values.size() > 10000) {
            throw new IllegalStateException(RCLONE_LIST_INVALID.code());
        }
    }

    private RemoteItem toRemoteItem(JsonNode value, String relative) {
        long size = value.path("Size").asLong(-1);
        if (size < 0) {
            throw new IllegalStateException(RCLONE_LIST_INVALID.code());
        }
        String id = value.path("ID").asText("");
        if (id.isBlank() || id.length() > 255) {
            id = "path:" + sha256(relative);
        }
        return new RemoteItem(id, relative, size, value.path("ModTime").asText(""));
    }

    private String relativePath(String root, String value) {
        String normalizedRoot = validPath(root);
        String normalizedValue = validPath(value);
        String prefix = normalizedRoot + "/";
        return normalizedValue.startsWith(prefix)
            ? validPath(normalizedValue.substring(prefix.length())) : normalizedValue;
    }

    /** 启动受控目录移动。 @param remoteKey 远端键 @param source 来源 @param target 目标 @return 后台任务 */
    public long startMove(String remoteKey, String source, String target) {
        JsonNode response = call("sync/move", Map.of("srcFs", remotePath(remoteKey, source),
            "dstFs", remotePath(remoteKey, target), "_async", true), Duration.ofSeconds(30));
        long id = response.path("jobid").asLong(0);
        if (id <= 0) {
            throw new IllegalStateException(RCLONE_JOB_INVALID.code());
        }
        return id;
    }

    /** 启动受控单文件移动。 @param remoteKey 远端键 @param source 来源 @param target 目标 @return 后台任务 */
    public long startMoveFile(String remoteKey, String source, String target) {
        JsonNode response = call("operations/movefile", Map.of("srcFs", remoteKey + ":",
            "srcRemote", validPath(source), "dstFs", remoteKey + ":",
            "dstRemote", validPath(target), "_async", true), Duration.ofSeconds(30));
        long id = response.path("jobid").asLong(0);
        if (id <= 0) throw new IllegalStateException(RCLONE_JOB_INVALID.code());
        return id;
    }

    /** 查询后台任务。 @param id rclone 任务标识 @return 状态 */
    public RemoteJob job(long id) {
        JsonNode response = call("job/status", Map.of("jobid", id), Duration.ofSeconds(30));
        boolean finished = response.path("finished").asBoolean(false);
        return new RemoteJob(id, finished, finished && response.path("success").asBoolean(false));
    }

    /** 停止后台任务。 @param id rclone 任务标识 */
    public void stop(long id) {
        if (id > 0) {
            call("job/stop", Map.of("jobid", id), Duration.ofSeconds(30));
        }
    }

    /** 清理尚未移动的隔离目录。 @param remoteKey 远端键 @param path 隔离路径 */
    public void purge(String remoteKey, String path) {
        call("operations/purge", Map.of("fs", remoteKey + ":", "remote", validPath(path)),
            Duration.ofMinutes(2));
    }

    private String remotePath(String remoteKey, String path) {
        if (!REMOTE.matcher(remoteKey).matches()) {
            throw new IllegalArgumentException(REMOTE_KEY_INVALID.code());
        }
        return remoteKey + ":" + validPath(path);
    }

    private String validPath(String value) {
        String path = value == null ? "" : value.strip().replace('\\', '/');
        if (path.isBlank() || path.length() > 1024 || path.startsWith("/") || path.contains("\u0000")) {
            throw new IllegalArgumentException(REMOTE_PATH_INVALID.code());
        }
        for (String part : path.split("/", -1)) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException(REMOTE_PATH_INVALID.code());
            }
        }
        return path;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private JsonNode call(String action, Map<String, Object> payload, Duration timeout) {
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(action)).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (!user.isBlank() || !password.isBlank()) {
                request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body().length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException(RCLONE_FAILURE.code());
            }
            JsonNode document = mapper.readTree(response.body());
            if (!document.isObject()) {
                throw new IllegalStateException(RCLONE_FAILURE.code());
            }
            return document;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(RCLONE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(RCLONE_FAILURE.code(), exception);
        }
    }
}
