package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.reader.config.ReaderRuntimeProperties;
import com.yuyutian.mytools.reader.mapper.BookSourceSearchCacheMapper;
import com.yuyutian.mytools.reader.mapper.SyncedBookSourceMapper;
import com.yuyutian.mytools.reader.model.BookSourceSearchCache;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeSearchModels;
import com.yuyutian.mytools.reader.model.SyncedBookSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户级书源异步全量搜索服务。
 */
@Service
public class BookSourceRuntimeSearchService {
    private static final Logger log = LoggerFactory.getLogger(BookSourceRuntimeSearchService.class);
    private static final long TASK_TTL_MILLIS = Duration.ofHours(2).toMillis();
    private static final long SEARCH_CACHE_TTL_MILLIS = Duration.ofDays(3).toMillis();
    private static final long FAILURE_CACHE_TTL_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final String CACHE_RESULT = "RESULT";
    private static final String CACHE_EMPTY = "EMPTY";
    private static final String CACHE_UNREADABLE = "UNREADABLE";
    private static final String CACHE_ERROR = "ERROR";
    private static final int MAX_SEARCH_CONCURRENCY = 20;
    private static final int MAX_POLL_RESULTS = 200;
    private final SyncedBookSourceMapper mapper;
    private final BookSourceSearchCacheMapper cacheMapper;
    private final ObjectMapper objectMapper;
    private final ReaderRuntimeClient runtimeClient;
    private final ReaderRuntimeProperties properties;
    private final BookSourceProbeQueryService probeQueryService;
    private final Counter cacheHitCounter;
    private final Counter cacheReadFailureCounter;
    private final Counter cacheWriteSuccessCounter;
    private final Counter cacheWriteFailureCounter;
    private final Counter cacheCleanupFailureCounter;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore validationSemaphore = new Semaphore(16);
    private final Map<String, SearchTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> activeTaskIds = new ConcurrentHashMap<>();
    private final Map<Long, String> synchronizedFingerprints = new ConcurrentHashMap<>();
    private final Map<Long, Object> userSyncLocks = new ConcurrentHashMap<>();
    private volatile long lastCacheCleanupAt;

    /**
     * 创建用户级书源异步搜索服务。
     *
     * @param mapper 书源数据访问接口
     * @param cacheMapper 搜索缓存数据访问接口
     * @param objectMapper JSON转换器
     * @param runtimeClient 规则执行器客户端
     * @param properties 执行器配置
     * @param probeQueryService DSH探测词分析服务
     * @param meterRegistry 指标注册器
     */
    public BookSourceRuntimeSearchService(SyncedBookSourceMapper mapper, BookSourceSearchCacheMapper cacheMapper,
                                          ObjectMapper objectMapper,
                                          ReaderRuntimeClient runtimeClient, ReaderRuntimeProperties properties,
                                          BookSourceProbeQueryService probeQueryService,
                                          MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.cacheMapper = cacheMapper;
        this.objectMapper = objectMapper;
        this.runtimeClient = runtimeClient;
        this.properties = properties;
        this.probeQueryService = probeQueryService;
        this.cacheHitCounter = cacheCounter(meterRegistry, "hit");
        this.cacheReadFailureCounter = cacheCounter(meterRegistry, "read_failure");
        this.cacheWriteSuccessCounter = cacheCounter(meterRegistry, "write_success");
        this.cacheWriteFailureCounter = cacheCounter(meterRegistry, "write_failure");
        this.cacheCleanupFailureCounter = cacheCounter(meterRegistry, "cleanup_failure");
    }

    /**
     * 启动遍历当前用户全部已启用书源的异步搜索。
     *
     * @param userId 用户ID
     * @param keyword 搜索关键词
     * @param page 页码
     * @param mode 搜索模式
     * @return 初始任务快照
     */
    public synchronized BookSourceRuntimeSearchModels.Task start(Long userId, String keyword, int page, String mode) {
        cleanupExpiredTasks();
        String normalizedKeyword = keyword.trim();
        String normalizedMode = mode == null || mode.isBlank() ? "FUZZY" : mode.trim().toUpperCase(Locale.ROOT);
        String activeKey = userId + "|" + normalizedMode + "|" + page + "|" + normalizedKeyword;
        String activeTaskId = activeTaskIds.get(activeKey);
        SearchTask active = activeTaskId == null ? null : tasks.get(activeTaskId);
        if (active != null && "RUNNING".equals(active.status)) return snapshot(active, 0, MAX_POLL_RESULTS);
        List<SourceSnapshot> sources = loadEnabledSources(userId);
        SearchTask task = new SearchTask(UUID.randomUUID().toString(), userId, normalizedKeyword, normalizedMode, page,
                sources.size(), activeKey);
        tasks.put(task.taskId, task);
        activeTaskIds.put(activeKey, task.taskId);
        List<SourceSnapshot> pendingSources = loadCachedSources(task, sources);
        task.pendingSources = pendingSources.size();
        if (pendingSources.isEmpty()) {
            complete(task);
            activeTaskIds.remove(task.activeKey, task.taskId);
            return snapshot(task, 0, MAX_POLL_RESULTS);
        }
        executor.submit(() -> run(task, sources, pendingSources));
        return snapshot(task, 0, MAX_POLL_RESULTS);
    }

    /**
     * 分页查询异步搜索任务的当前结果。
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param offset 已接收结果数量
     * @param limit 本次接收上限
     * @return 任务快照
     */
    public BookSourceRuntimeSearchModels.Task find(Long userId, String taskId, int offset, int limit) {
        SearchTask task = tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)
                || System.currentTimeMillis() - task.updatedAt > TASK_TTL_MILLIS) {
            tasks.remove(taskId);
            throw new BusinessException(ErrorCode.READER_010);
        }
        return snapshot(task, Math.max(0, offset), Math.max(1, Math.min(MAX_POLL_RESULTS, limit)));
    }

    /**
     * 终止当前用户拥有的搜索任务。
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 终止后的任务快照
     */
    public BookSourceRuntimeSearchModels.Task cancel(Long userId, String taskId) {
        SearchTask task = tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) throw new BusinessException(ErrorCode.READER_010);
        task.cancelled = true;
        task.status = "CANCELLED";
        task.message = "搜索已终止";
        task.updatedAt = System.currentTimeMillis();
        activeTaskIds.remove(task.activeKey, task.taskId);
        return snapshot(task, 0, MAX_POLL_RESULTS);
    }

    private void run(SearchTask task, List<SourceSnapshot> sources, List<SourceSnapshot> pendingSources) {
        try {
            synchronizeSources(task.userId, sources);
            if (task.cancelled) return;
            List<String> terms = "PROBE".equals(task.mode)
                    ? probeQueryService.analyze(task.userId, task.keyword, () -> task.cancelled)
                    : List.of(task.keyword);
            if (task.cancelled) return;
            if (terms.isEmpty()) throw new BusinessException(ErrorCode.READER_009);
            task.message = "PROBE".equals(task.mode) ? "探测词分析完成，正在执行书源搜索" : "正在执行书源搜索";
            int concurrency = Math.max(1, Math.min(MAX_SEARCH_CONCURRENCY, properties.getSearchConcurrency()));
            Semaphore semaphore = new Semaphore(concurrency);
            List<Future<?>> searches = new ArrayList<>();
            for (SourceSnapshot source : pendingSources) {
                searches.add(executor.submit(() -> searchSource(task, source, terms, semaphore)));
            }
            for (Future<?> search : searches) {
                try {
                    search.get();
                } catch (Exception ignored) {
                    // 单个书源执行异常已经计入失败数量，不中断全量任务。
                }
            }
            if (!task.cancelled) {
                complete(task);
            }
        } catch (Exception exception) {
            if (!task.cancelled) {
                task.status = "FAILED";
                task.message = "PROBE".equals(task.mode) ? "DSH探测搜索暂不可用" : "书源执行服务暂不可用";
            }
        } finally {
            task.updatedAt = System.currentTimeMillis();
            activeTaskIds.remove(task.activeKey, task.taskId);
        }
    }

    private void searchSource(SearchTask task, SourceSnapshot source, List<String> terms, Semaphore semaphore) {
        boolean acquired = false;
        try {
            if (task.cancelled) return;
            semaphore.acquire();
            acquired = true;
            List<BookSourceRuntimeSearchModels.SearchResult> values = new ArrayList<>();
            Map<String, Boolean> sourceResultKeys = new ConcurrentHashMap<>();
            for (String term : terms) {
                if (task.cancelled) return;
                // 同一书源的探测词串行执行，不占用额外并发槽位。
                for (BookSourceRuntimeSearchModels.SearchResult value : runtimeClient.search(task.userId,
                        source.url, source.name, term, task.page)) {
                    if (!matches(value, term, task.mode)) continue;
                    if (sourceResultKeys.putIfAbsent(canonicalResultKey(value), Boolean.TRUE) == null) values.add(value);
                }
            }
            semaphore.release();
            acquired = false;
            List<Future<?>> validations = new ArrayList<>();
            for (BookSourceRuntimeSearchModels.SearchResult value : values) {
                validations.add(executor.submit(() -> validateAndAdd(task, value)));
            }
            List<BookSourceRuntimeSearchModels.SearchResult> accepted = new ArrayList<>();
            for (Future<?> validation : validations) {
                if (task.cancelled) break;
                try {
                    Object validated = validation.get();
                    if (validated instanceof BookSourceRuntimeSearchModels.SearchResult value) accepted.add(value);
                } catch (Exception ignored) {
                    // 单本图书试读失败不会阻断同一书源的其他结果。
                }
            }
            if (!task.cancelled) {
                if (values.isEmpty()) {
                    // 书源明确返回空列表时写入三天负缓存。
                    saveCache(task, source, CACHE_EMPTY, List.of(), SEARCH_CACHE_TTL_MILLIS);
                } else if (accepted.isEmpty()) {
                    // 候选结果全部不可读时记录无可用结果，避免下次重复验证。
                    saveCache(task, source, CACHE_UNREADABLE, List.of(), SEARCH_CACHE_TTL_MILLIS);
                } else {
                    saveCache(task, source, CACHE_RESULT, accepted, SEARCH_CACHE_TTL_MILLIS);
                }
            }
        } catch (Exception exception) {
            if (!task.cancelled) {
                task.failedSources.incrementAndGet();
                // 执行异常只短期退避，服务恢复后允许自动重试。
                saveCache(task, source, CACHE_ERROR, List.of(), FAILURE_CACHE_TTL_MILLIS);
            }
        } finally {
            if (acquired) semaphore.release();
            task.processedSources.incrementAndGet();
            task.updatedAt = System.currentTimeMillis();
        }
    }

    private BookSourceRuntimeSearchModels.SearchResult validateAndAdd(
            SearchTask task, BookSourceRuntimeSearchModels.SearchResult value) {
        boolean acquired = false;
        try {
            if (task.cancelled) return null;
            validationSemaphore.acquire();
            acquired = true;
            // 搜索规则成功不代表目录和正文仍然可用，实际试读成功后才增量返回。
            if (!isReadable(task.userId, value) || task.cancelled) return null;
            addResult(task, value);
            return value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) validationSemaphore.release();
        }
    }

    private void addResult(SearchTask task, BookSourceRuntimeSearchModels.SearchResult value) {
        synchronized (task.results) {
            String key = canonicalResultKey(value);
            if (task.resultKeys.putIfAbsent(key, Boolean.TRUE) == null) task.results.add(value);
        }
    }

    private List<SourceSnapshot> loadCachedSources(SearchTask task, List<SourceSnapshot> sources) {
        Map<String, BookSourceSearchCache> caches = new ConcurrentHashMap<>();
        try {
            for (BookSourceSearchCache cache : cacheMapper.findValidForSearch(task.userId, normalize(task.keyword),
                    task.mode, task.page, System.currentTimeMillis())) caches.put(cache.sourceId(), cache);
        } catch (Exception exception) {
            cacheReadFailureCounter.increment();
            log.error("Failed to read book source search cache: userId={}, mode={}, page={}",
                    task.userId, task.mode, task.page, exception);
            // 缓存查询异常时全部书源回退实时搜索。
        }
        List<SourceSnapshot> pending = new ArrayList<>();
        for (SourceSnapshot source : sources) {
            BookSourceSearchCache cache = caches.get(source.id);
            if (cache == null || cache.sourceRevision() != source.revision) {
                pending.add(source);
                continue;
            }
            try {
                if (!isSupportedCacheStatus(cache.cacheStatus())) {
                    log.warn("Ignored book source search cache with unsupported status: sourceId={}, status={}",
                            source.id, cache.cacheStatus());
                    pending.add(source);
                    continue;
                }
                List<BookSourceRuntimeSearchModels.SearchResult> values = objectMapper.readValue(cache.resultsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                                BookSourceRuntimeSearchModels.SearchResult.class));
                values.forEach(value -> addResult(task, value));
                task.cachedSources.incrementAndGet();
                task.processedSources.incrementAndGet();
                if (CACHE_ERROR.equals(cache.cacheStatus())) task.failedSources.incrementAndGet();
                cacheHitCounter.increment();
            } catch (Exception exception) {
                cacheReadFailureCounter.increment();
                log.warn("Failed to parse book source search cache: sourceId={}", source.id, exception);
                // 单条缓存损坏仅让对应书源重新查询并覆盖缓存。
                pending.add(source);
            }
        }
        return pending;
    }

    private void complete(SearchTask task) {
        task.status = "COMPLETED";
        String progressMessage = "，缓存命中" + task.cachedSources.get() + "个，实际查询"
                + task.pendingSources + "个，失败" + task.failedSources.get() + "个";
        task.message = task.results.isEmpty() ? "全部书源搜索完成，未找到结果" + progressMessage
                : "全部书源搜索完成" + progressMessage;
        task.updatedAt = System.currentTimeMillis();
    }

    private void saveCache(SearchTask task, SourceSnapshot source, String cacheStatus,
                           List<BookSourceRuntimeSearchModels.SearchResult> values, long ttlMillis) {
        try {
            long now = System.currentTimeMillis();
            String resultsJson = objectMapper.writeValueAsString(values);
            cacheMapper.upsert(new BookSourceSearchCache(task.userId, normalize(task.keyword), task.mode,
                    source.id, task.page, source.revision, cacheStatus, resultsJson, values.size(), now,
                    now + ttlMillis));
            cacheWriteSuccessCounter.increment();
        } catch (Exception exception) {
            cacheWriteFailureCounter.increment();
            log.error("Failed to write book source search cache: userId={}, sourceId={}, mode={}, page={}, status={}",
                    task.userId, source.id, task.mode, task.page, cacheStatus, exception);
            // 缓存写入失败不影响本次已经完成的搜索结果。
        }
    }

    private boolean isSupportedCacheStatus(String cacheStatus) {
        return CACHE_RESULT.equals(cacheStatus) || CACHE_EMPTY.equals(cacheStatus)
                || CACHE_UNREADABLE.equals(cacheStatus) || CACHE_ERROR.equals(cacheStatus);
    }

    private Counter cacheCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("mytools.reader.book.source.search.cache")
                .description("Book source search cache operations")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    boolean matches(BookSourceRuntimeSearchModels.SearchResult result, String keyword, String mode) {
        String expected = normalize(keyword);
        String name = normalize(result.name());
        String author = normalize(result.author());
        if ("EXACT".equals(mode)) return name.equals(expected) || author.equals(expected);
        return name.contains(expected) || author.contains(expected)
                || normalize(result.intro()).contains(expected) || normalize(result.lastChapter()).contains(expected);
    }

    String canonicalResultKey(BookSourceRuntimeSearchModels.SearchResult result) {
        String name = normalize(result.name()).replaceAll("[\\p{P}\\p{S}]", "");
        // 跨书源作者字段经常缺失或带有不同前后缀，搜索结果按规范化书名去重。
        return name;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    boolean isReadable(Long userId, BookSourceRuntimeSearchModels.SearchResult result) {
        try {
            var catalog = runtimeClient.catalog(userId, result.sourceUrl(), result.bookUrl());
            if (catalog.chapters().isEmpty()) return false;
            // App默认打开首章，因此首章不可读的搜索结果不能交给客户端。
            var chapter = catalog.chapters().getFirst();
            var content = runtimeClient.content(userId, result.sourceUrl(), chapter.resourceUri(), chapter.index());
            return !content.text().isBlank() || !content.imageUrls().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void synchronizeSources(Long userId, List<SourceSnapshot> sources) {
        String fingerprint = sources.stream()
                .sorted(Comparator.comparing(SourceSnapshot::url))
                .map(value -> value.url + ":" + value.revision)
                .reduce("", (left, right) -> left + "|" + right);
        Object lock = userSyncLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            if (fingerprint.equals(synchronizedFingerprints.get(userId))) return;
            runtimeClient.replaceSources(userId, sources.stream().map(SourceSnapshot::json).toList());
            synchronizedFingerprints.put(userId, fingerprint);
        }
    }

    private List<SourceSnapshot> loadEnabledSources(Long userId) {
        List<SourceSnapshot> output = new ArrayList<>();
        for (SyncedBookSource source : mapper.findAllByUserId(userId)) {
            if (source.isDeleted()) continue;
            try {
                JsonNode root = objectMapper.readTree(source.getSnapshotJson());
                if (!root.isObject() || !root.path("enabled").asBoolean(true)) continue;
                String url = root.path("bookSourceUrl").asText("").trim();
                if (url.isBlank()) continue;
                String name = root.path("bookSourceName").asText(url).trim();
                output.add(new SourceSnapshot(source.getSyncKey(), url, name.isBlank() ? url : name, root,
                        source.getRevision() == null ? 0 : source.getRevision()));
            } catch (Exception ignored) {
                // 数据库中的单个损坏快照不会阻断其他书源搜索。
            }
        }
        return output;
    }

    private BookSourceRuntimeSearchModels.Task snapshot(SearchTask task, int offset, int limit) {
        List<BookSourceRuntimeSearchModels.SearchResult> current;
        int totalResults;
        synchronized (task.results) {
            int start = Math.min(offset, task.results.size());
            int end = Math.min(task.results.size(), start + limit);
            current = List.copyOf(task.results.subList(start, end));
            totalResults = task.results.size();
        }
        int nextOffset = offset + current.size();
        return new BookSourceRuntimeSearchModels.Task(task.taskId, task.status, task.processedSources.get(),
                task.totalSources, task.cachedSources.get(), task.pendingSources, task.failedSources.get(),
                totalResults, current, nextOffset,
                nextOffset < totalResults, task.message, task.updatedAt);
    }

    private void cleanupExpiredTasks() {
        long cutoff = System.currentTimeMillis() - TASK_TTL_MILLIS;
        tasks.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff);
        long now = System.currentTimeMillis();
        if (now - lastCacheCleanupAt < Duration.ofHours(1).toMillis()) return;
        try {
            cacheMapper.deleteExpired(now);
            lastCacheCleanupAt = now;
        } catch (Exception exception) {
            cacheCleanupFailureCounter.increment();
            log.warn("Failed to clean expired book source search cache", exception);
            // 清理失败不影响搜索，过期记录仍会被查询条件排除。
        }
    }

    private record SourceSnapshot(String id, String url, String name, JsonNode json, long revision) {
    }

    private static final class SearchTask {
        private final String taskId;
        private final Long userId;
        private final String keyword;
        private final String mode;
        private final int page;
        private volatile int totalSources;
        private volatile int pendingSources;
        private final String activeKey;
        private final AtomicInteger processedSources = new AtomicInteger();
        private final AtomicInteger failedSources = new AtomicInteger();
        private final AtomicInteger cachedSources = new AtomicInteger();
        private final List<BookSourceRuntimeSearchModels.SearchResult> results = new ArrayList<>();
        private final Map<String, Boolean> resultKeys = new ConcurrentHashMap<>();
        private volatile String status = "RUNNING";
        private volatile String message = "正在准备用户书源";
        private volatile long updatedAt = System.currentTimeMillis();
        private volatile boolean cancelled;

        private SearchTask(String taskId, Long userId, String keyword, String mode, int page,
                           int totalSources, String activeKey) {
            this.taskId = taskId;
            this.userId = userId;
            this.keyword = keyword;
            this.mode = mode;
            this.page = page;
            this.totalSources = totalSources;
            this.activeKey = activeKey;
        }
    }
}
