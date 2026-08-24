package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.reader.model.BookSourceDiscoveryModels;
import com.yuyutian.mytools.reader.task.ReaderDiscoverySidecarRequested;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用确定性站点适配器和结构化仓库导入书源。
 */
@Service
@RequiredArgsConstructor
public class BookSourceDiscoveryService {

    private static final long TASK_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_RUNNING_PER_USER = 2;
    private static final int MAX_SOURCE_JSON_LENGTH = 131_072;
    private static final int MAX_REPOSITORY_INPUT_LENGTH = 20 * 1024 * 1024;
    private static final int REPOSITORY_SAVE_BATCH_SIZE = 100;
    private static final int MAX_REPOSITORY_REDIRECTS = 3;
    private static final Pattern YCKCEO_SOURCE_PATTERN = Pattern.compile(
            "(?i)/yuedu/shuyuans?/(?:content|json)/id/(\\d+)\\.(?:html|json)");

    private final ObjectMapper objectMapper;
    private final BookSourceSyncService bookSourceSyncService;
    private final List<BookSourceSiteAdapter> siteAdapters;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, MutableTask> tasks = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "book-source-discovery");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService repositoryExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 创建异步书源导入任务。
     *
     * @param userId 用户ID
     * @param url 书源仓库或已支持站点地址
     * @return 任务快照
     */
    public BookSourceDiscoveryModels.Task start(Long userId, String url) {
        cleanupExpiredTasks();
        URI target = requirePublicOrigin(url);
        long running = tasks.values().stream()
                .filter(task -> task.userId.equals(userId) && "RUNNING".equals(task.status))
                .count();
        if (running >= MAX_RUNNING_PER_USER) throw new BusinessException(ErrorCode.READER_008);
        MutableTask task = new MutableTask(UUID.randomUUID().toString(), userId, target.toString());
        tasks.put(task.taskId, task);
        // 旁路复制已完成公网校验的地址，新服务失败时旧发现仍继续执行。
        eventPublisher.publishEvent(new ReaderDiscoverySidecarRequested(
                task.taskId, userId, target.toString()));
        executor.submit(() -> discover(task));
        return task.snapshot();
    }

    /**
     * 查询当前用户拥有的书源导入任务。
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 任务快照
     */
    public BookSourceDiscoveryModels.Task find(Long userId, String taskId) {
        cleanupExpiredTasks();
        if (taskId == null || !taskId.matches("[0-9a-f-]{36}")) {
            throw new BusinessException(ErrorCode.READER_007);
        }
        MutableTask task = tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) throw new BusinessException(ErrorCode.READER_007);
        return task.snapshot();
    }

    /**
     * 关闭后台任务线程。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        repositoryExecutor.shutdownNow();
    }

    private void discover(MutableTask task) {
        try {
            URI target = URI.create(task.url);
            // 标准JSON仓库直接进入导入器，不经过任何模型或智能体。
            if (isYckceoRepository(target)) {
                ImportSummary summary = importYckceoRepository(task, target);
                task.complete(summaryMessage(summary));
                return;
            }
            if (isJsonResource(target)) {
                ImportSummary summary = importRepositoryPayload(task,
                        fetchJson(target, MAX_REPOSITORY_INPUT_LENGTH));
                if (summary.saved() == 0) throw new BusinessException(ErrorCode.READER_007);
                task.complete(summaryMessage(summary));
                return;
            }
            BookSourceSiteAdapter adapter = siteAdapters.stream()
                    .filter(value -> value.supports(target))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.READER_007));
            String snapshot = adapter.createSnapshot(target, objectMapper);
            int saved = bookSourceSyncService.saveDiscoveredSources(task.userId, List.of(snapshot));
            if (saved != 1) throw new BusinessException(ErrorCode.READER_007);
            task.complete("\u5df2\u4fdd\u5b58 1 \u4e2a\u4e66\u6e90");
        } catch (RuntimeException exception) {
            task.fail("\u8be5\u5730\u5740\u4e0d\u662f\u53d7\u652f\u6301\u7684\u4e66\u6e90\u4ed3\u5e93\u6216\u7ad9\u70b9");
        }
    }

    private String summaryMessage(ImportSummary summary) {
        return "\u5df2\u4fdd\u5b58 " + summary.saved() + " \u4e2a\u4e66\u6e90\uff0c\u5ffd\u7565 "
                + summary.rejected() + " \u4e2a\u65e0\u6548\u9879";
    }

    private boolean isJsonResource(URI target) {
        String path = target.getPath() == null ? "" : target.getPath().toLowerCase();
        return path.endsWith(".json");
    }

    private boolean isYckceoRepository(URI target) {
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase();
        String path = target.getPath() == null ? "" : target.getPath().toLowerCase();
        return ("yckceo.com".equals(host) || host.endsWith(".yckceo.com"))
                && (path.startsWith("/yuedu/shuyuan/") || path.startsWith("/yuedu/shuyuans/"));
    }

    private ImportSummary importYckceoRepository(MutableTask task, URI target) {
        Matcher direct = YCKCEO_SOURCE_PATTERN.matcher(target.getPath());
        if (direct.matches()) {
            URI jsonUri = repositoryJsonUri(target, direct.group(1));
            int maximumBytes = isYckceoCollection(target)
                    ? MAX_REPOSITORY_INPUT_LENGTH : MAX_SOURCE_JSON_LENGTH;
            return importRepositoryPayload(task, fetchJson(jsonUri, maximumBytes));
        }
        // 合集索引可能包含数百个大文件，只允许用户明确选择一个合集。
        if (isYckceoCollection(target)) throw new BusinessException(ErrorCode.READER_007);
        String html = fetchPage(target);
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = YCKCEO_SOURCE_PATTERN.matcher(html);
        while (matcher.find()) identifiers.add(matcher.group(1));
        if (identifiers.isEmpty()) throw new BusinessException(ErrorCode.READER_007);
        List<CompletableFuture<String>> requests = identifiers.stream()
                .map(identifier -> CompletableFuture.supplyAsync(
                        () -> fetchJson(repositoryJsonUri(target, identifier), MAX_SOURCE_JSON_LENGTH),
                        repositoryExecutor).exceptionally(ignored -> ""))
                .toList();
        ImportSummary summary = new ImportSummary(0, 0, 0);
        for (CompletableFuture<String> request : requests) {
            String payload = request.join();
            summary = summary.add(payload.isBlank()
                    ? new ImportSummary(1, 0, 1) : importRepositoryPayload(task, payload));
            task.progress("\u5df2\u5904\u7406 " + summary.processed() + " \u4e2a\u4e66\u6e90\uff0c\u5df2\u4fdd\u5b58 "
                    + summary.saved() + " \u4e2a");
        }
        if (summary.saved() == 0) throw new BusinessException(ErrorCode.READER_007);
        return summary;
    }

    private URI repositoryJsonUri(URI target, String identifier) {
        String namespace = isYckceoCollection(target) ? "shuyuans" : "shuyuan";
        return URI.create(target.getScheme() + "://" + target.getAuthority()
                + "/yuedu/" + namespace + "/json/id/" + identifier + ".json");
    }

    private boolean isYckceoCollection(URI target) {
        String path = target.getPath() == null ? "" : target.getPath().toLowerCase();
        return path.startsWith("/yuedu/shuyuans/");
    }

    private String fetchJson(URI uri, int maximumBytes) {
        URI current = uri;
        try {
            for (int redirectCount = 0; redirectCount <= MAX_REPOSITORY_REDIRECTS; redirectCount++) {
                // 每次跳转都重新校验目标，避免仓库地址被用于访问内网。
                current = requirePublicOrigin(current.toString());
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json,text/plain;q=0.8")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) MyTools/1.0")
                        .GET().build();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (isRedirect(response.statusCode())) {
                    try (InputStream ignored = response.body()) {
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new BusinessException(ErrorCode.READER_007));
                        current = current.resolve(location);
                    }
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    response.body().close();
                    throw new BusinessException(ErrorCode.READER_007);
                }
                try (InputStream body = response.body()) {
                    byte[] bytes = body.readNBytes(maximumBytes + 1);
                    if (bytes.length > maximumBytes) throw new BusinessException(ErrorCode.READER_007);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
            throw new BusinessException(ErrorCode.READER_007);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.READER_007);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.READER_007);
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private ImportSummary importRepositoryPayload(MutableTask task, String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            List<JsonNode> sources = new ArrayList<>();
            if (root != null && root.isArray()) root.forEach(sources::add);
            else if (root != null) sources.add(root);
            List<String> batch = new ArrayList<>(REPOSITORY_SAVE_BATCH_SIZE);
            int processed = 0;
            int saved = 0;
            int rejected = 0;
            for (JsonNode source : sources) {
                processed++;
                String snapshot = repositorySnapshot(source);
                if (snapshot == null) rejected++;
                else batch.add(snapshot);
                if (batch.size() >= REPOSITORY_SAVE_BATCH_SIZE) {
                    int batchSaved = bookSourceSyncService.saveDiscoveredSources(task.userId, batch);
                    saved += batchSaved;
                    rejected += batch.size() - batchSaved;
                    batch.clear();
                    task.progress("\u5df2\u5904\u7406 " + processed + "/" + sources.size()
                            + "\uff0c\u5df2\u4fdd\u5b58 " + saved + " \u4e2a\u4e66\u6e90");
                }
            }
            if (!batch.isEmpty()) {
                int batchSaved = bookSourceSyncService.saveDiscoveredSources(task.userId, batch);
                saved += batchSaved;
                rejected += batch.size() - batchSaved;
            }
            task.progress("\u5df2\u5904\u7406 " + processed + "/" + sources.size()
                    + "\uff0c\u5df2\u4fdd\u5b58 " + saved + " \u4e2a\u4e66\u6e90");
            return new ImportSummary(processed, saved, rejected);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.READER_007);
        }
    }

    private String repositorySnapshot(JsonNode source) {
        if (!source.isObject() || !source.path("bookSourceUrl").isTextual()
                || !source.path("bookSourceName").isTextual()) return null;
        String url = source.path("bookSourceUrl").asText();
        try {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return objectMapper.writeValueAsString(source);
        } catch (IllegalArgumentException | JsonProcessingException ignored) {
            return null;
        }
    }

    private String fetchPage(URI uri) {
        requirePublicOrigin(uri.toString());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 "
                        + "Chrome/120 Mobile Safari/537.36 MyTools/1.0")
                .GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !response.headers().firstValue("content-type").orElse("").toLowerCase().contains("html")) {
                response.body().close();
                throw new BusinessException(ErrorCode.READER_007);
            }
            try (InputStream body = response.body()) {
                return new String(body.readNBytes(512_001), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.READER_007);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.READER_007);
        }
    }

    private URI requirePublicOrigin(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim()).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new BusinessException(ErrorCode.READER_007);
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new BusinessException(ErrorCode.READER_007);
                }
            }
            return uri;
        } catch (IllegalArgumentException | UnknownHostException exception) {
            throw new BusinessException(ErrorCode.READER_007);
        }
    }

    private void cleanupExpiredTasks() {
        long cutoff = System.currentTimeMillis() - TASK_TTL_MILLIS;
        tasks.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff
                && !"RUNNING".equals(entry.getValue().status));
    }

    private static final class MutableTask {
        private final String taskId;
        private final Long userId;
        private final String url;
        private volatile String status = "RUNNING";
        private volatile String sourceJson = "";
        private volatile String message = "";
        private volatile long updatedAt = System.currentTimeMillis();

        private MutableTask(String taskId, Long userId, String url) {
            this.taskId = taskId;
            this.userId = userId;
            this.url = url;
        }

        private void complete(String value) {
            sourceJson = "";
            message = value;
            status = "SUCCEEDED";
            updatedAt = System.currentTimeMillis();
        }

        private void progress(String value) {
            message = value;
            updatedAt = System.currentTimeMillis();
        }

        private void fail(String value) {
            message = value;
            status = "FAILED";
            updatedAt = System.currentTimeMillis();
        }

        private BookSourceDiscoveryModels.Task snapshot() {
            return new BookSourceDiscoveryModels.Task(taskId, status, sourceJson, message, updatedAt);
        }
    }

    private record ImportSummary(int processed, int saved, int rejected) {
        private ImportSummary add(ImportSummary other) {
            return new ImportSummary(processed + other.processed, saved + other.saved, rejected + other.rejected);
        }
    }
}
