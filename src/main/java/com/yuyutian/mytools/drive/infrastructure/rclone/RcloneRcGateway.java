package com.yuyutian.mytools.drive.infrastructure.rclone;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 仅连接回环地址且只调用白名单 RC 方法的 rclone 网关。
 */
@Component
@RequiredArgsConstructor
public class RcloneRcGateway implements RcloneGateway {

    private static final Pattern REMOTE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${rclone.rc-url:http://127.0.0.1:5572}")
    private String rcUrl;

    @Value("${rclone.rc-user:}")
    private String rcUser;

    @Value("${rclone.rc-password:}")
    private String rcPassword;

    @Value("${rclone.serve-url:http://127.0.0.1:5573}")
    private String serveUrl;

    @Value("${rclone.serve-user:}")
    private String serveUser;

    @Value("${rclone.serve-password:}")
    private String servePassword;

    private URI baseUri;
    private URI serveBaseUri;

    /**
     * 启动时拒绝任何非回环 RC 地址。
     */
    @PostConstruct
    public void validateConfiguration() {
        baseUri = validateLoopbackEndpoint(rcUrl, "Rclone RC");
        serveBaseUri = validateLoopbackEndpoint(serveUrl, "Rclone serve");
    }

    private URI validateLoopbackEndpoint(String value, String label) {
        URI candidate = URI.create(value);
        String host = candidate.getHost();
        if (!"http".equals(candidate.getScheme()) || host == null
                || !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                || candidate.getUserInfo() != null || candidate.getQuery() != null || candidate.getFragment() != null
                || !(candidate.getPath().isEmpty() || "/".equals(candidate.getPath()))) {
            throw new IllegalStateException(label + " must use a loopback HTTP endpoint");
        }
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    /** {@inheritDoc} */
    @Override
    public List<RcloneItem> list(String remoteKey, String path) {
        validateRemote(remoteKey, path);
        JsonNode response = call("operations/list", Map.of(
                "fs", remoteKey + ":",
                "remote", path,
                "opt", Map.of("recurse", false, "showOrigIDs", true, "showHash", false)));
        JsonNode values = response.path("list");
        if (!values.isArray() || values.size() > 10_000) {
            throw new BusinessException(ErrorCode.DRIVE_003);
        }
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .map(value -> objectMapper.convertValue(value, RcItem.class))
                .map(this::normalize)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public RcloneDirectorySize size(String remoteKey, String path) {
        validateRemote(remoteKey, path);
        String fs = remoteKey + ":" + path;
        JsonNode response = call("operations/size", Map.of("fs", fs));
        long count = response.path("count").asLong(-1L);
        long bytes = response.path("bytes").asLong(-1L);
        if (count < 0 || bytes < 0) {
            throw new BusinessException(ErrorCode.DRIVE_003);
        }
        return new RcloneDirectorySize(count, bytes);
    }

    /** {@inheritDoc} */
    @Override
    public RcloneContent open(String remoteKey, String path, long offset, long count) {
        validateRemote(remoteKey, path);
        if (path == null || path.isBlank() || offset < 0 || count == 0
                || (count < 0 && offset != 0) || (count > 0 && offset > Long.MAX_VALUE - count)) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }
        // rclone serve指向组合远端，一级目录固定为drive_account.remote_key。
        String normalizedPath = remoteKey + "/" + normalizePath(path);
        try {
            HttpRequest.Builder builder = serveRequestBuilder(normalizedPath);
            if (count >= 0) {
                builder.header("Range", "bytes=" + offset + "-" + (offset + count - 1L));
            }
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int expectedStatus = count < 0 ? 200 : 206;
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (response.statusCode() != expectedStatus || (count >= 0 && contentLength != count)) {
                response.body().close();
                throw new BusinessException(ErrorCode.DRIVE_003);
            }
            return new RcloneContent(response.body(), contentLength);
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.DRIVE_003);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.DRIVE_003);
        }
    }

    private JsonNode call(String method, Object body) {
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(body);
            HttpRequest.Builder builder = requestBuilder(method, requestBody).header("Accept", "application/json");
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body().length == 0 || response.body().length > MAX_RESPONSE_BYTES) {
                throw new BusinessException(ErrorCode.DRIVE_003);
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.DRIVE_003);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.DRIVE_003);
        }
    }

    private HttpRequest.Builder requestBuilder(String method, byte[] requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(method))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
        if (!rcUser.isBlank() || !rcPassword.isBlank()) {
            String credentials = Base64.getEncoder().encodeToString(
                    (rcUser + ":" + rcPassword).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + credentials);
        }
        return builder;
    }

    private HttpRequest.Builder serveRequestBuilder(String path) {
        try {
            URI encoded = new URI(serveBaseUri.getScheme(), null, serveBaseUri.getHost(), serveBaseUri.getPort(),
                    "/" + path, null, null);
            HttpRequest.Builder builder = HttpRequest.newBuilder(encoded).timeout(Duration.ofMinutes(5)).GET();
            if (!serveUser.isBlank() || !servePassword.isBlank()) {
                String credentials = Base64.getEncoder().encodeToString(
                        (serveUser + ":" + servePassword).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + credentials);
            }
            return builder;
        } catch (URISyntaxException ex) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }
    }

    private RcloneItem normalize(RcItem item) {
        String path = normalizePath(item.path());
        String name = item.name() == null ? "" : item.name().trim();
        if (name.isBlank() || name.length() > 512 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new BusinessException(ErrorCode.DRIVE_003);
        }
        OffsetDateTime modifiedAt = null;
        if (item.modTime() != null && !item.modTime().isBlank()) {
            try {
                modifiedAt = OffsetDateTime.parse(item.modTime());
            } catch (DateTimeParseException ignored) {
                // 远端时间不可用时保留为空，不影响目录浏览。
            }
        }
        return new RcloneItem(path, name, Math.max(0L, item.size()), bounded(item.mimeType(), 255),
                modifiedAt, item.directory(), bounded(item.id(), 255));
    }

    private void validateRemote(String remoteKey, String path) {
        if (!REMOTE_KEY_PATTERN.matcher(remoteKey).matches()) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }
        normalizePath(path);
    }

    private String normalizePath(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.length() > 2048 || value.contains(":")) {
            throw new BusinessException(ErrorCode.DRIVE_004);
        }
        for (String part : value.split("/", -1)) {
            if ("..".equals(part) || part.chars().anyMatch(character -> character < 32 || character == 127)) {
                throw new BusinessException(ErrorCode.DRIVE_004);
            }
        }
        return value;
    }

    private String bounded(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RcItem(
            @JsonProperty("Path") String path,
            @JsonProperty("Name") String name,
            @JsonProperty("Size") long size,
            @JsonProperty("MimeType") String mimeType,
            @JsonProperty("ModTime") String modTime,
            @JsonProperty("IsDir") boolean directory,
            @JsonProperty("OrigID") String id) {
    }
}
