package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CacheView;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CreatePrefetch;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.PrefetchView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookGenerationView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookExportView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookPlaybackManifest;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookBookAnalysisView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookVoicePlanView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookVoiceCatalogView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookPronunciationDictionaryView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookGeneration;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookExport;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookVoiceRevision;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookSpeakerRevision;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookPronunciationRevision;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CatalogView;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateManagedImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.ImportView;
import com.yuyutian.mytools.gateway.model.ReaderSearchGatewayModels.CreateSearch;
import com.yuyutian.mytools.gateway.model.ReaderSearchGatewayModels.SearchView;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateDiscovery;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateHealthCheck;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.DiscoveryView;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.HealthCheckView;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.GatewayNotFoundException;
import com.yuyutian.mytools.gateway.service.ReaderGatewayClient;
import com.yuyutian.mytools.gateway.service.BookSourceRuntimeSearchGatewayClient;
import com.yuyutian.mytools.gateway.service.AudiobookPlaybackTicketService;
import com.yuyutian.mytools.gateway.service.AudiobookVoicePreviewTicketService;
import com.yuyutian.mytools.gateway.service.AudiobookExportTicketService;
import com.yuyutian.mytools.gateway.service.MediaGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 从可信主体注入 owner 的 Reader Gateway 路由。
 */
@RestController
@RequestMapping("/api/app/v1/reader")
public class ReaderGatewayController {

    /** 可安全交给受管有声书导入链路的媒体 MIME 类型。 */
    private static final Set<String> SUPPORTED_MANAGED_AUDIOBOOK_MIME_TYPES = Set.of(
            "text/plain", "application/epub+zip");

    private final GatewayProperties properties;
    private final ReaderGatewayClient client;
    private final BookSourceRuntimeSearchGatewayClient runtimeSearchClient;
    private final AudiobookPlaybackTicketService audiobookTickets;
    private final AudiobookVoicePreviewTicketService audiobookVoicePreviewTickets;
    private final AudiobookExportTicketService audiobookExportTickets;
    private final MediaGatewayClient mediaClient;

    /**
     * 创建 Reader Gateway 控制器。
     */
    public ReaderGatewayController(GatewayProperties properties, ReaderGatewayClient client) {
        this(properties, client, null, new AudiobookPlaybackTicketService(), new AudiobookVoicePreviewTicketService(),
                new AudiobookExportTicketService(), null);
    }

    /**
     * 创建包含书源运行时搜索代理的 Reader Gateway 控制器。
     *
     * @param properties Gateway配置
     * @param client Reader服务客户端
     * @param runtimeSearchClient 书源运行时搜索客户端
     */
    public ReaderGatewayController(GatewayProperties properties, ReaderGatewayClient client,
                                   BookSourceRuntimeSearchGatewayClient runtimeSearchClient,
                                   AudiobookPlaybackTicketService audiobookTickets) {
        this(properties, client, runtimeSearchClient, audiobookTickets, new AudiobookVoicePreviewTicketService(),
                new AudiobookExportTicketService(), null);
    }

    /**
     * 创建包含书源、播放和受管媒体桥接依赖的 Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client Reader 服务客户端
     * @param runtimeSearchClient 书源运行时搜索客户端
     * @param audiobookTickets 有声书播放票据服务
     * @param mediaClient Media Library 客户端
     */
    public ReaderGatewayController(GatewayProperties properties, ReaderGatewayClient client,
                                   BookSourceRuntimeSearchGatewayClient runtimeSearchClient,
                                   AudiobookPlaybackTicketService audiobookTickets,
                                   MediaGatewayClient mediaClient) {
        this(properties, client, runtimeSearchClient, audiobookTickets, new AudiobookVoicePreviewTicketService(),
                new AudiobookExportTicketService(), mediaClient);
    }

    /**
     * 创建包含导出下载票据服务的 Reader Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client Reader 服务客户端
     * @param runtimeSearchClient 书源运行时搜索客户端
     * @param audiobookTickets 有声书播放票据服务
     * @param audiobookExportTickets 有声书导出票据服务
     * @param mediaClient Media Library 客户端
     */
    public ReaderGatewayController(GatewayProperties properties, ReaderGatewayClient client,
                                   BookSourceRuntimeSearchGatewayClient runtimeSearchClient,
                                   AudiobookPlaybackTicketService audiobookTickets,
                                   AudiobookExportTicketService audiobookExportTickets,
                                   MediaGatewayClient mediaClient) {
        this(properties, client, runtimeSearchClient, audiobookTickets, new AudiobookVoicePreviewTicketService(),
                audiobookExportTickets, mediaClient);
    }

    /**
     * 创建包含音色样音票据服务的 Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client Reader 服务客户端
     * @param runtimeSearchClient 书源运行时搜索客户端
     * @param audiobookTickets 章节播放票据服务
     * @param audiobookVoicePreviewTickets 音色样音播放票据服务
     * @param audiobookExportTickets 有声书导出票据服务
     * @param mediaClient Media Library 客户端
     */
    @Autowired
    public ReaderGatewayController(GatewayProperties properties, ReaderGatewayClient client,
                                   BookSourceRuntimeSearchGatewayClient runtimeSearchClient,
                                   AudiobookPlaybackTicketService audiobookTickets,
                                   AudiobookVoicePreviewTicketService audiobookVoicePreviewTickets,
                                   AudiobookExportTicketService audiobookExportTickets,
                                   MediaGatewayClient mediaClient) {
        this.properties = properties;
        this.client = client;
        this.runtimeSearchClient = runtimeSearchClient;
        this.audiobookTickets = audiobookTickets;
        this.audiobookVoicePreviewTickets = audiobookVoicePreviewTickets;
        this.audiobookExportTickets = audiobookExportTickets;
        this.mediaClient = mediaClient;
    }

    /**
     * 查询当前主体书架。
     */
    @GetMapping("/shelves")
    public List<Map<String, Object>> shelves(@RequestParam(defaultValue = "false") boolean includeDeleted,
                                             HttpServletRequest request) {
        return list("shelves", includeDeleted, request);
    }

    /** 查询当前主体书源快照。 */
    @GetMapping("/sources")
    public List<Map<String, Object>> sources(HttpServletRequest request) {
        requireAllowed(request);
        return client.sources(principal(request).userId(), correlation(request));
    }

    /** 保存当前主体书源快照。 */
    @PutMapping("/sources")
    public Map<String, Object> saveSource(@Valid @RequestBody SourceRequest body, HttpServletRequest request) {
        requireAllowed(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", principal(request).userId());
        payload.put("syncKey", body.syncKey());
        payload.put("sourceUrl", body.sourceUrl());
        payload.put("snapshotJson", body.snapshotJson());
        payload.put("deleted", body.deleted());
        return client.saveSource(payload, correlation(request));
    }

    /**
     * 批量保存当前主体书源快照。
     *
     * @param body 批量书源请求
     * @param request HTTP请求
     * @return 批量同步回执
     */
    @PutMapping("/sources/batch")
    public Map<String, Object> saveSources(@Valid @RequestBody SourceBatchRequest body,
                                           HttpServletRequest request) {
        requireAllowed(request);
        long ownerId = principal(request).userId();
        List<Map<String, Object>> payloads = body.sources().stream().map(source -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ownerId", ownerId);
            payload.put("syncKey", source.syncKey());
            payload.put("sourceUrl", source.sourceUrl());
            payload.put("snapshotJson", source.snapshotJson());
            payload.put("deleted", source.deleted());
            return payload;
        }).toList();
        return client.saveSources(payloads, correlation(request));
    }

    /** 启动全部已启用书源的并发搜索。 */
    @PostMapping("/source-search")
    public Map<String, Object> startRuntimeSearch(@Valid @RequestBody RuntimeSearchRequest body,
                                                  HttpServletRequest request) {
        requireAllowed(request);
        return requiredRuntimeSearchClient().start(principal(request).userId(), Map.of("keyword", body.keyword(),
                "page", body.page(), "mode", body.mode()), correlation(request));
    }

    /** 查询书源并发搜索任务。 */
    @GetMapping("/source-search/{taskId}")
    public Map<String, Object> runtimeSearch(@PathVariable UUID taskId,
                                             @RequestParam(defaultValue = "0") @Min(0) int offset,
                                             @RequestParam(defaultValue = "200") @Min(1) int limit,
                                             HttpServletRequest request) {
        requireAllowed(request);
        return requiredRuntimeSearchClient().find(principal(request).userId(), taskId.toString(), offset,
                Math.min(limit, 200), correlation(request));
    }

    /** 取消书源并发搜索任务。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/source-search/{taskId}")
    public Map<String, Object> cancelRuntimeSearch(@PathVariable UUID taskId, HttpServletRequest request) {
        requireAllowed(request);
        return requiredRuntimeSearchClient().cancel(principal(request).userId(), taskId.toString(),
                correlation(request));
    }

    private BookSourceRuntimeSearchGatewayClient requiredRuntimeSearchClient() {
        if (runtimeSearchClient == null) throw new IllegalStateException("Reader Runtime search is unavailable");
        return runtimeSearchClient;
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

    /** 执行书源目录规则。 */
    @PostMapping("/source-runtime/catalog")
    public Map<String, Object> sourceCatalog(@Valid @RequestBody RuntimeCatalogRequest body,
                                             HttpServletRequest request) {
        requireAllowed(request);
        return client.sourceCatalog(principal(request).userId(), body.sourceUrl(), body.bookUrl(),
                correlation(request));
    }

    /** 执行书源正文规则。 */
    @PostMapping("/source-runtime/content")
    public Map<String, Object> sourceContent(@Valid @RequestBody RuntimeContentRequest body,
                                             HttpServletRequest request) {
        requireAllowed(request);
        return client.sourceContent(principal(request).userId(), body.sourceUrl(), body.chapterUrl(),
                body.chapterIndex(), correlation(request));
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
     * 从当前主体拥有的 Media Library TXT 或 EPUB 文件创建受管电子书导入。
     *
     * @param body App 请求
     * @param request HTTP 请求
     * @return 导入视图
     */
    @PostMapping("/managed-ebook-imports")
    public ImportView createManagedImport(@Valid @RequestBody CreateManagedImport body,
                                          HttpServletRequest request) {
        requireAllowed(request);
        if (mediaClient == null || !properties.mediaRouteEnabled()) {
            throw new GatewayRouteDisabledException();
        }
        GatewayPrincipal principal = principal(request);
        var media = mediaClient.view(principal.userId(), body.mediaItemId(), correlation(request));
        if (!"READY".equals(media.status()) || !SUPPORTED_MANAGED_AUDIOBOOK_MIME_TYPES.contains(
                media.mimeType().toLowerCase(java.util.Locale.ROOT))) {
            throw new GatewayNotFoundException();
        }
        return client.createManagedImport(principal.userId(), body, media, correlation(request));
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
     * 创建当前主体的有声书生成运行。
     *
     * @param body App 请求，不含所有者标识
     * @param request HTTP 请求
     * @return 有声书生成运行
     */
    @PostMapping("/audiobook-generations")
    public AudiobookGenerationView createAudiobookGeneration(@Valid @RequestBody CreateAudiobookGeneration body,
                                                             HttpServletRequest request) {
        requireAllowed(request);
        return client.createAudiobookGeneration(principal(request).userId(), body, correlation(request));
    }

    /**
     * 为当前主体以人工选择的已启用音色创建不可变修订版。
     *
     * @param id 已完成源版本标识
     * @param body App 修订请求
     * @param request HTTP 请求
     * @return 新建或复用的修订版本
     */
    @PostMapping("/audiobook-generations/{id}/voice-revisions")
    public AudiobookGenerationView createAudiobookVoiceRevision(@PathVariable UUID id,
                                                                 @Valid @RequestBody CreateAudiobookVoiceRevision body,
                                                                 HttpServletRequest request) {
        requireAllowed(request);
        return client.createAudiobookVoiceRevision(principal(request).userId(), id, body, correlation(request));
    }

    /**
     * 用当前版本已有角色、旁白或未知类别修订一条低置信度归因，并仅重新合成所在章节。
     *
     * @param id 已完成源版本标识
     * @param body 人工归因修订请求
     * @param request HTTP 请求
     * @return 已受理或复用的不可变修订版本
     */
    @PostMapping("/audiobook-generations/{id}/speaker-revisions")
    public AudiobookGenerationView createAudiobookSpeakerRevision(@PathVariable UUID id,
                                                                    @Valid @RequestBody CreateAudiobookSpeakerRevision body,
                                                                    HttpServletRequest request) {
        requireAllowed(request);
        return client.createAudiobookSpeakerRevision(principal(request).userId(), id, body, correlation(request));
    }

    /**
     * 为当前主体完成版本中的中文词条创建读音修订，并只重新合成精确命中的冻结章节。
     *
     * @param id 已完成源版本标识
     * @param body 读音词典修订请求
     * @param request HTTP 请求
     * @return 已受理或复用的不可变修订版本
     */
    @PostMapping("/audiobook-generations/{id}/pronunciation-revisions")
    public AudiobookGenerationView createAudiobookPronunciationRevision(@PathVariable UUID id,
                                                                         @Valid @RequestBody CreateAudiobookPronunciationRevision body,
                                                                         HttpServletRequest request) {
        requireAllowed(request);
        return client.createAudiobookPronunciationRevision(principal(request).userId(), id, body,
                correlation(request));
    }

    /**
     * 为当前主体已完成的有声书版本创建或复用异步 ZIP 导出任务。
     *
     * @param id 已完成 generation 标识
     * @param body App 请求
     * @param request HTTP 请求
     * @return 导出任务摘要
     */
    @PostMapping("/audiobook-generations/{id}/exports")
    public AudiobookExportView createAudiobookExport(@PathVariable UUID id,
                                                     @Valid @RequestBody CreateAudiobookExport body,
                                                     HttpServletRequest request) {
        requireAllowed(request);
        return client.createAudiobookExport(principal(request).userId(), id, body, correlation(request));
    }

    /**
     * 查询当前主体的异步 ZIP 导出任务。
     *
     * @param id generation 标识
     * @param exportId 导出标识
     * @param request HTTP 请求
     * @return 导出任务摘要
     */
    @GetMapping("/audiobook-generations/{id}/exports/{exportId}")
    public AudiobookExportView audiobookExport(@PathVariable UUID id, @PathVariable UUID exportId,
                                               HttpServletRequest request) {
        requireAllowed(request);
        return client.audiobookExport(principal(request).userId(), id, exportId, correlation(request));
    }

    /**
     * 为当前主体一个已完成 ZIP 归档签发无授权头下载票据。
     *
     * @param id generation 标识
     * @param exportId 导出标识
     * @param request HTTP 请求
     * @return 下载票据和下载路径
     */
    @PostMapping("/audiobook-generations/{id}/exports/{exportId}/download-ticket")
    public Map<String, Object> audiobookExportDownloadTicket(@PathVariable UUID id, @PathVariable UUID exportId,
                                                              HttpServletRequest request) {
        requireAllowed(request);
        GatewayPrincipal principal = principal(request);
        AudiobookExportView export = client.audiobookExport(principal.userId(), id, exportId, correlation(request));
        if (!"COMPLETED".equals(export.status())) {
            throw new GatewayNotFoundException();
        }
        var ticket = audiobookExportTickets.issue(principal.userId(), id, exportId);
        return Map.of("ticket", ticket.token(),
                "downloadPath", "/api/app/v1/audiobook-export/tickets/" + ticket.token(),
                "expiresAt", ticket.expiresAt().toString());
    }

    /**
     * 查询当前主体的一条有声书生成运行。
     *
     * @param id 生成运行标识
     * @param request HTTP 请求
     * @return 有声书生成运行
     */
    @GetMapping("/audiobook-generations/{id}")
    public AudiobookGenerationView audiobookGeneration(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.audiobookGeneration(principal(request).userId(), id, correlation(request));
    }

    /**
     * 查询当前主体一个版本冻结的读音词典，用于审核与再次修订。
     *
     * @param id 有声书版本标识
     * @param request HTTP 请求
     * @return 词典摘要
     */
    @GetMapping("/audiobook-generations/{id}/pronunciations")
    public AudiobookPronunciationDictionaryView audiobookPronunciationDictionary(@PathVariable UUID id,
                                                                                   HttpServletRequest request) {
        requireAllowed(request);
        return client.audiobookPronunciationDictionary(principal(request).userId(), id, correlation(request));
    }

    /**
     * 请求取消当前主体正在处理的有声书生成任务。
     *
     * @param id 生成运行标识
     * @param request HTTP 请求
     * @return 取消已受理后的运行摘要
     */
    @PostMapping("/audiobook-generations/{id}/cancel")
    public AudiobookGenerationView cancelAudiobookGeneration(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.cancelAudiobookGeneration(principal(request).userId(), id, correlation(request));
    }

    /**
     * 从当前主体有声书生成的最后一个终态阶段重新提交任务。
     *
     * @param id 生成运行标识
     * @param request HTTP 请求
     * @return 重试已受理后的运行摘要
     */
    @PostMapping("/audiobook-generations/{id}/retry")
    public AudiobookGenerationView retryAudiobookGeneration(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.retryAudiobookGeneration(principal(request).userId(), id, correlation(request));
    }

    /**
     * 查询当前主体的版本化章节播放清单。
     *
     * @param id 生成运行标识
     * @param request HTTP 请求
     * @return 章节播放清单
     */
    @GetMapping("/audiobook-generations/{id}/playback-manifest")
    public AudiobookPlaybackManifest audiobookPlaybackManifest(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.audiobookPlaybackManifest(principal(request).userId(), id, correlation(request));
    }

    /**
     * 查询当前主体可审核的全书人物、关系和说话人归因结果。
     *
     * @param id 生成运行标识
     * @param request HTTP 请求
     * @return 全书结构化分析视图
     */
    @GetMapping("/audiobook-generations/{id}/book-analysis")
    public AudiobookBookAnalysisView audiobookBookAnalysis(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.audiobookBookAnalysis(principal(request).userId(), id, correlation(request));
    }

    /**
     * 查询当前主体可审核的冻结旁白与角色音色计划。
     *
     * @param id 生成运行标识
     * @param request HTTP 请求
     * @return 冻结音色计划
     */
    @GetMapping("/audiobook-generations/{id}/voice-plan")
    public AudiobookVoicePlanView audiobookVoicePlan(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.audiobookVoicePlan(principal(request).userId(), id, correlation(request));
    }

    /**
     * 查询当前主体可用于人工替换的已启用音色目录。
     *
     * @param id 有声书版本标识
     * @param request HTTP 请求
     * @return 不含供应商密钥的音色目录
     */
    @GetMapping("/audiobook-generations/{id}/voice-catalog")
    public AudiobookVoiceCatalogView audiobookVoiceCatalog(@PathVariable UUID id, HttpServletRequest request) {
        requireAllowed(request);
        return client.audiobookVoiceCatalog(principal(request).userId(), id, correlation(request));
    }

    /**
     * 为当前所有者一条已审核音色样音签发系统播放器可用的短期播放票据。
     *
     * @param id 生成运行标识
     * @param provider 音色供应商标识
     * @param voiceType 音色标识
     * @param request HTTP 请求
     * @return 样音播放票据和无授权头流地址
     */
    @PostMapping("/audiobook-generations/{id}/voice-previews/play-ticket")
    public Map<String, Object> audiobookVoicePreviewTicket(@PathVariable UUID id,
                                                            @RequestParam @NotBlank @Size(max = 64) String provider,
                                                            @RequestParam @NotBlank @Size(max = 256) String voiceType,
                                                            HttpServletRequest request) {
        requireAllowed(request);
        GatewayPrincipal principal = principal(request);
        AudiobookGenerationView generation = client.audiobookGeneration(principal.userId(), id, correlation(request));
        if (!"COMPLETED".equals(generation.status())) {
            throw new GatewayNotFoundException();
        }
        boolean previewAvailable = client.audiobookVoiceCatalog(principal.userId(), id, correlation(request)).voices()
                .stream().anyMatch(voice -> voice.provider().equals(provider) && voice.voiceType().equals(voiceType)
                        && voice.previewAvailable());
        if (!previewAvailable) {
            throw new GatewayNotFoundException();
        }
        var ticket = audiobookVoicePreviewTickets.issue(principal.userId(), id, provider, voiceType);
        return Map.of("ticket", ticket.token(),
                "streamPath", "/api/app/v1/audiobook-voice-preview/tickets/" + ticket.token(),
                "expiresAt", ticket.expiresAt().toString());
    }

    /**
     * 为当前所有者的一节已就绪章节签发系统播放器可用的短期播放票据。
     *
     * @param id 生成运行标识
     * @param chapterIndex 章节序号
     * @param request HTTP 请求
     * @return 播放票据和无授权头流地址
     */
    @PostMapping("/audiobook-generations/{id}/chapters/{chapterIndex}/play-ticket")
    public Map<String, Object> audiobookPlayTicket(@PathVariable UUID id, @PathVariable int chapterIndex,
                                                    HttpServletRequest request) {
        requireAllowed(request);
        GatewayPrincipal principal = principal(request);
        AudiobookPlaybackManifest manifest = client.audiobookPlaybackManifest(principal.userId(), id,
                correlation(request));
        boolean ready = manifest.chapters().stream().anyMatch(chapter -> chapter.index() == chapterIndex
                && "READY".equals(chapter.availability()) && chapter.audioAssetId() != null);
        if (!ready) {
            throw new GatewayNotFoundException();
        }
        var ticket = audiobookTickets.issue(principal.userId(), id, chapterIndex);
        return Map.of("ticket", ticket.token(),
                "streamPath", "/api/app/v1/audiobook-playback/tickets/" + ticket.token(),
                "expiresAt", ticket.expiresAt().toString());
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

    /** 书源目录请求。 */
    public record RuntimeCatalogRequest(@NotBlank @Size(max = 4096) String sourceUrl,
                                        @NotBlank @Size(max = 4096) String bookUrl) { }

    /** 书源正文请求。 */
    public record RuntimeContentRequest(@NotBlank @Size(max = 4096) String sourceUrl,
                                        @NotBlank @Size(max = 4096) String chapterUrl,
                                        @Min(0) int chapterIndex) { }

    /**
     * Gateway 书源写入请求，不允许客户端指定 owner。
     */
    public record SourceRequest(@NotBlank @Size(max = 255) String syncKey,
                                @NotBlank @Size(max = 2000) String sourceUrl,
                                @NotBlank @Size(max = 524288) String snapshotJson,
                                boolean deleted, Long updatedAt, Long revision) {
    }

    /** Gateway 书源批量写入请求。 */
    public record SourceBatchRequest(@NotEmpty @Size(max = 200) List<@Valid SourceRequest> sources) {
    }

    /** Gateway书源并发搜索请求。 */
    public record RuntimeSearchRequest(@NotBlank @Size(max = 200) String keyword,
                                       @Min(1) int page,
                                       @jakarta.validation.constraints.Pattern(regexp = "EXACT|FUZZY") String mode) {
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
