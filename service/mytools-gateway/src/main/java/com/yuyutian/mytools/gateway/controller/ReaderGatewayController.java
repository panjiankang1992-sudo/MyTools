package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CacheView;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CreatePrefetch;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.PrefetchView;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CatalogView;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.ImportView;
import com.yuyutian.mytools.gateway.model.ReaderSearchGatewayModels.CreateSearch;
import com.yuyutian.mytools.gateway.model.ReaderSearchGatewayModels.SearchView;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateDiscovery;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateHealthCheck;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.DiscoveryView;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.HealthCheckView;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.ReaderGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从可信主体注入 owner 的 Reader Gateway 路由。
 */
@RestController
@RequestMapping("/api/app/v1/reader")
public class ReaderGatewayController {

    private final GatewayProperties properties;
    private final ReaderGatewayClient client;

    /**
     * 创建 Reader Gateway 控制器。
     */
    public ReaderGatewayController(GatewayProperties properties, ReaderGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 查询当前主体书架。
     */
    @GetMapping("/shelves")
    public List<Map<String, Object>> shelves(@RequestParam(defaultValue = "false") boolean includeDeleted,
                                             HttpServletRequest request) {
        return list("shelves", includeDeleted, request);
    }

    /** 查询当前主体已发布的电子书索引。 @param request HTTP 请求 @return 索引 */
    @GetMapping("/library-index")
    public List<Map<String, Object>> libraryIndex(HttpServletRequest request) {
        requireAllowed(request);
        return client.libraryIndex(principal(request).userId(), correlation(request));
    }

    /**
     * 写入当前主体书架。
     */
    @PostMapping("/shelves")
    public Map<String, Object> saveShelf(@Valid @RequestBody ShelfRequest body, HttpServletRequest request) {
        requireAllowed(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", principal(request).userId());
        payload.put("bookKey", body.bookKey());
        payload.put("metadata", body.metadata());
        payload.put("deleted", body.deleted());
        payload.put("expectedVersion", body.expectedVersion());
        return save("shelves", payload, request);
    }

    /**
     * 查询当前主体阅读进度。
     */
    @GetMapping("/progress")
    public List<Map<String, Object>> progress(@RequestParam(defaultValue = "false") boolean includeDeleted,
                                              HttpServletRequest request) {
        return list("progress", includeDeleted, request);
    }

    /**
     * 写入当前主体阅读进度。
     */
    @PostMapping("/progress")
    public Map<String, Object> saveProgress(@Valid @RequestBody ProgressRequest body, HttpServletRequest request) {
        requireAllowed(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", principal(request).userId());
        payload.put("bookKey", body.bookKey());
        payload.put("chapterIndex", body.chapterIndex());
        payload.put("chapterUrl", body.chapterUrl());
        payload.put("position", body.position());
        payload.put("deleted", body.deleted());
        payload.put("expectedVersion", body.expectedVersion());
        return save("progress", payload, request);
    }

    /**
     * 查询当前主体阅读标记。
     */
    @GetMapping("/markers")
    public List<Map<String, Object>> markers(@RequestParam(defaultValue = "false") boolean includeDeleted,
                                             HttpServletRequest request) {
        return list("markers", includeDeleted, request);
    }

    /**
     * 写入当前主体阅读标记。
     */
    @PostMapping("/markers")
    public Map<String, Object> saveMarker(@Valid @RequestBody MarkerRequest body, HttpServletRequest request) {
        requireAllowed(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("markerId", body.markerId());
        payload.put("ownerId", principal(request).userId());
        payload.put("bookKey", body.bookKey());
        payload.put("markerType", body.markerType());
        payload.put("chapterIndex", body.chapterIndex());
        payload.put("position", body.position());
        payload.put("note", body.note());
        payload.put("deleted", body.deleted());
        payload.put("expectedVersion", body.expectedVersion());
        return save("markers", payload, request);
    }

    /**
     * 创建书源搜索任务。
     *
     * @param body 请求
     * @param request HTTP 请求
     * @return 搜索
     */
    @PostMapping("/book-searches")
    public SearchView createSearch(@Valid @RequestBody CreateSearch body, HttpServletRequest request) {
        requireAllowed(request);
        return client.createSearch(principal(request).userId(), body, correlation(request));
    }

    /**
     * 查询书源搜索。
     *
     * @param id 搜索
     * @param request HTTP 请求
     * @return 搜索
     */
    @GetMapping("/book-searches/{id}")
    public SearchView search(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.search(principal(request).userId(), id, correlation(request));
    }

    /**
     * 取消书源搜索。
     *
     * @param id 搜索
     * @param request HTTP 请求
     * @return 搜索
     */
    @PostMapping("/book-searches/{id}/cancel")
    public SearchView cancelSearch(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.cancelSearch(principal(request).userId(), id, correlation(request));
    }

    /**
     * 创建电子书导入任务。
     *
     * @param body 创建请求
     * @param request HTTP 请求
     * @return 导入视图
     */
    @PostMapping("/ebook-imports")
    public ImportView createImport(@Valid @RequestBody CreateImport body, HttpServletRequest request) {
        requireAllowed(request);
        return client.createImport(principal(request).userId(), body, correlation(request));
    }

    /**
     * 查询电子书导入任务。
     *
     * @param id 导入标识
     * @param request HTTP 请求
     * @return 导入视图
     */
    @GetMapping("/ebook-imports/{id}")
    public ImportView importView(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.importView(principal(request).userId(), id, correlation(request));
    }

    /**
     * 取消电子书导入任务。
     *
     * @param id 导入标识
     * @param request HTTP 请求
     * @return 导入视图
     */
    @PostMapping("/ebook-imports/{id}/cancel")
    public ImportView cancelImport(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.cancelImport(principal(request).userId(), id, correlation(request));
    }

    /**
     * 查询电子书目录。
     *
     * @param id 导入标识
     * @param request HTTP 请求
     * @return 目录视图
     */
    @GetMapping("/ebook-imports/{id}/catalog")
    public CatalogView importCatalog(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.importCatalog(principal(request).userId(), id, correlation(request));
    }

    /**
     * 创建书源发现任务。
     *
     * @param body 创建请求
     * @param request HTTP 请求
     * @return 发现视图
     */
    @PostMapping("/source-discoveries")
    public DiscoveryView createDiscovery(@Valid @RequestBody CreateDiscovery body, HttpServletRequest request) {
        requireAllowed(request);
        return client.createDiscovery(principal(request).userId(), body, correlation(request));
    }

    /**
     * 查询书源发现任务。
     *
     * @param id 发现标识
     * @param request HTTP 请求
     * @return 发现视图
     */
    @GetMapping("/source-discoveries/{id}")
    public DiscoveryView discovery(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.discovery(principal(request).userId(), id, correlation(request));
    }

    /**
     * 取消书源发现任务。
     *
     * @param id 发现标识
     * @param request HTTP 请求
     * @return 发现视图
     */
    @PostMapping("/source-discoveries/{id}/cancel")
    public DiscoveryView cancelDiscovery(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.cancelDiscovery(principal(request).userId(), id, correlation(request));
    }

    /**
     * 创建书源健康检查任务。
     *
     * @param body 创建请求
     * @param request HTTP 请求
     * @return 健康检查视图
     */
    @PostMapping("/source-health-checks")
    public HealthCheckView createHealthCheck(@Valid @RequestBody CreateHealthCheck body,
                                             HttpServletRequest request) {
        requireAllowed(request);
        return client.createHealthCheck(principal(request).userId(), body, correlation(request));
    }

    /**
     * 查询书源健康检查任务。
     *
     * @param id 健康检查标识
     * @param request HTTP 请求
     * @return 健康检查视图
     */
    @GetMapping("/source-health-checks/{id}")
    public HealthCheckView healthCheck(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.healthCheck(principal(request).userId(), id, correlation(request));
    }

    /**
     * 取消书源健康检查任务。
     *
     * @param id 健康检查标识
     * @param request HTTP 请求
     * @return 健康检查视图
     */
    @PostMapping("/source-health-checks/{id}/cancel")
    public HealthCheckView cancelHealthCheck(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.cancelHealthCheck(principal(request).userId(), id, correlation(request));
    }

    /**
     * 创建章节预取任务。
     *
     * @param body 创建请求
     * @param request HTTP 请求
     * @return 预取任务视图
     */
    @PostMapping("/chapter-prefetches")
    public PrefetchView createPrefetch(@Valid @RequestBody CreatePrefetch body, HttpServletRequest request) {
        requireAllowed(request);
        return client.createPrefetch(principal(request).userId(), body, correlation(request));
    }

    /**
     * 查询章节预取任务。
     *
     * @param id 预取标识
     * @param request HTTP 请求
     * @return 预取任务视图
     */
    @GetMapping("/chapter-prefetches/{id}")
    public PrefetchView prefetch(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.prefetch(principal(request).userId(), id, correlation(request));
    }

    /**
     * 取消章节预取任务。
     *
     * @param id 预取标识
     * @param request HTTP 请求
     * @return 预取任务视图
     */
    @PostMapping("/chapter-prefetches/{id}/cancel")
    public PrefetchView cancelPrefetch(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.cancelPrefetch(principal(request).userId(), id, correlation(request));
    }

    /**
     * 查询章节缓存。
     *
     * @param sourceId 书源标识
     * @param bookUrl 图书地址
     * @param chapterUrl 章节地址
     * @param request HTTP 请求
     * @return 缓存视图
     */
    @GetMapping("/chapter-cache")
    public CacheView chapterCache(@RequestParam UUID sourceId,
                                  @RequestParam @NotBlank @Size(max = 4096) String bookUrl,
                                  @RequestParam @NotBlank @Size(max = 4096) String chapterUrl,
                                  HttpServletRequest request) {
        requireAllowed(request);
        return client.chapterCache(principal(request).userId(), sourceId, bookUrl, chapterUrl,
                correlation(request));
    }

    private List<Map<String, Object>> list(String resource, boolean includeDeleted, HttpServletRequest request) {
        requireAllowed(request);
        return client.list(resource, principal(request).userId(), includeDeleted, correlation(request));
    }

    private Map<String, Object> save(String resource, Map<String, Object> payload, HttpServletRequest request) {
        requireAllowed(request);
        return client.save(resource, payload, correlation(request));
    }

    private GatewayPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) {
            throw new GatewayUnauthorizedException();
        }
        return principal;
    }

    private String correlation(HttpServletRequest request) {
        return request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE).toString();
    }

    private void requireAllowed(HttpServletRequest request) {
        // Controller 再次校验主体名单，防止过滤器配置或调用链变化绕过灰度边界。
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)
                || !properties.readerTenantAllowed(principal.userId())) {
            throw new GatewayRouteDisabledException();
        }
    }

    /**
     * Gateway 书架写入请求，不允许客户端指定 owner。
     */
    public record ShelfRequest(@NotBlank @Size(max = 512) String bookKey,
                               @NotNull Map<String, Object> metadata,
                               boolean deleted, @Positive Long expectedVersion) {
    }

    /**
     * Gateway 阅读进度写入请求，不允许客户端指定 owner。
     */
    public record ProgressRequest(@NotBlank @Size(max = 512) String bookKey, @Min(0) int chapterIndex,
                                  @Size(max = 4096) String chapterUrl, @NotNull Map<String, Object> position,
                                  boolean deleted, @Positive Long expectedVersion) {
    }

    /**
     * Gateway 阅读标记写入请求，不允许客户端指定 owner。
     */
    public record MarkerRequest(@NotNull UUID markerId, @NotBlank @Size(max = 512) String bookKey,
                                @NotBlank @Size(max = 32) String markerType, @Min(0) int chapterIndex,
                                @NotNull Map<String, Object> position, @Size(max = 10000) String note,
                                boolean deleted, @Positive Long expectedVersion) {
    }
}
