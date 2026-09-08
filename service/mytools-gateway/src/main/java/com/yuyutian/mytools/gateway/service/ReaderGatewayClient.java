package com.yuyutian.mytools.gateway.service;

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
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CatalogView;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateManagedImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.ImportView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaView;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateDiscovery;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateHealthCheck;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.DiscoveryView;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.HealthCheckView;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletResponse;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.yuyutian.mytools.gateway.model.ReaderSearchGatewayModels.*;

/**
 * 只转发 Gateway 构造载荷的 Reader 内部客户端。
 */
@Component
public class ReaderGatewayClient {

    private static final Pattern READER_ERROR_CODE = Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"(READER_[0-9]{3})\\\"");

    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    /**
     * 创建 Reader Gateway 客户端。
     */
    public ReaderGatewayClient(RestTemplate restTemplate, GatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 查询当前主体的一类 Reader 状态。
     */
    public List<Map<String, Object>> list(String resource, long ownerId, boolean includeDeleted,
                                          String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/reader-state/" + resource)
                .queryParam("ownerId", ownerId).queryParam("includeDeleted", includeDeleted)
                .toUriString();
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(null, correlationId),
                new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        return response.getBody() == null ? List.of() : response.getBody();
    }

    /** 查询当前用户已发布书库索引。 @param ownerId 用户 @param correlationId 关联标识 @return 索引 */
    public List<Map<String, Object>> libraryIndex(long ownerId, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/library-index")
                .queryParam("ownerId", ownerId).toUriString();
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(null, correlationId),
                new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        return response.getBody() == null ? List.of() : response.getBody();
    }

    /**
     * 写入 Gateway 已绑定 owner 的 Reader 状态。
     */
    public Map<String, Object> save(String resource, Map<String, Object> payload, String correlationId) {
        var response = restTemplate.exchange(root() + "/api/v1/reader-state/" + resource,
                HttpMethod.POST, entity(payload, correlationId),
                new ParameterizedTypeReference<Map<String, Object>>() { });
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    /** 查询当前主体书源快照。 */
    public List<Map<String, Object>> sources(long ownerId, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/reader-state/sources")
                .queryParam("ownerId", ownerId).toUriString();
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(null, correlationId),
                new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        return response.getBody() == null ? List.of() : response.getBody();
    }

    /** 保存当前主体书源快照。 */
    public Map<String, Object> saveSource(Map<String, Object> payload, String correlationId) {
        var response = restTemplate.exchange(root() + "/api/v1/reader-state/sources", HttpMethod.PUT,
                entity(payload, correlationId), new ParameterizedTypeReference<Map<String, Object>>() { });
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    /**
     * 批量保存当前主体的书源快照。
     *
     * @param payloads 已注入所有者的书源列表
     * @param correlationId 关联标识
     * @return 批量同步回执
     */
    public Map<String, Object> saveSources(List<Map<String, Object>> payloads, String correlationId) {
        var response = restTemplate.exchange(root() + "/api/v1/reader-state/sources/batch", HttpMethod.PUT,
                entity(Map.of("sources", payloads), correlationId),
                new ParameterizedTypeReference<Map<String, Object>>() { });
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    /**
     * 创建书源搜索任务。
     *
     * @param ownerId 所有者
     * @param request 请求
     * @param correlationId 关联标识
     * @return 搜索
     */
    public SearchView createSearch(long ownerId, CreateSearch request, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("keyword", request.keyword());
        payload.put("mode", request.mode());
        payload.put("page", request.page());
        payload.put("sources", request.sources());
        return exchangeSearch(root() + "/api/v1/book-searches", HttpMethod.POST, payload, correlationId);
    }

    /**
     * 查询书源搜索。
     *
     * @param ownerId 所有者
     * @param id 搜索
     * @param correlationId 关联标识
     * @return 搜索
     */
    public SearchView search(long ownerId, UUID id, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/book-searches/" + id)
                .queryParam("ownerId", ownerId).toUriString();
        return exchangeSearch(url, HttpMethod.GET, null, correlationId);
    }

    /**
     * 取消书源搜索。
     *
     * @param ownerId 所有者
     * @param id 搜索
     * @param correlationId 关联标识
     * @return 搜索
     */
    public SearchView cancelSearch(long ownerId, UUID id, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/book-searches/" + id + "/cancel")
                .queryParam("ownerId", ownerId).toUriString();
        return exchangeSearch(url, HttpMethod.POST, null, correlationId);
    }

    /** 执行书源目录规则。 */
    public Map<String, Object> sourceCatalog(long ownerId, String sourceUrl, String bookUrl,
                                              String correlationId) {
        return runtime("catalog", Map.of("ownerId", ownerId, "sourceUrl", sourceUrl,
                "bookUrl", bookUrl), correlationId);
    }

    /** 执行书源正文规则。 */
    public Map<String, Object> sourceContent(long ownerId, String sourceUrl, String chapterUrl,
                                              int chapterIndex, String correlationId) {
        return runtime("content", Map.of("ownerId", ownerId, "sourceUrl", sourceUrl,
                "chapterUrl", chapterUrl, "chapterIndex", chapterIndex), correlationId);
    }

    /**
     * 创建电子书导入任务。
     *
     * @param ownerId 所有者标识
     * @param request 创建请求
     * @param correlationId 关联标识
     * @return 导入视图
     */
    public ImportView createImport(long ownerId, CreateImport request, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("sourceId", request.sourceId());
        payload.put("bookUrl", request.bookUrl());
        payload.put("title", request.title());
        payload.put("author", request.author());
        return exchangeImport(root() + "/api/v1/ebook-imports", HttpMethod.POST, payload, correlationId);
    }

    /**
     * 创建已经由 Gateway 按当前主体核验过的受管媒体电子书导入。
     *
     * @param ownerId 所有者标识
     * @param request App 请求
     * @param media 已核验媒体快照
     * @param correlationId 关联标识
     * @return 导入视图
     */
    public ImportView createManagedImport(long ownerId, CreateManagedImport request, MediaView media,
                                          String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("mediaItemId", media.id());
        payload.put("mediaAssetId", media.assetId());
        payload.put("title", media.displayName());
        payload.put("mimeType", media.mimeType());
        payload.put("sizeBytes", media.sizeBytes());
        payload.put("contentSha256", media.contentSha256());
        payload.put("rightsConfirmed", request.rightsConfirmed());
        return exchangeImport(root() + "/api/v1/ebook-imports/managed-media", HttpMethod.POST,
                payload, correlationId);
    }

    /**
     * 查询电子书导入任务。
     *
     * @param ownerId 所有者标识
     * @param id 导入标识
     * @param correlationId 关联标识
     * @return 导入视图
     */
    public ImportView importView(long ownerId, UUID id, String correlationId) {
        String url = ownerUrl(root() + "/api/v1/ebook-imports/" + id, ownerId);
        return exchangeImport(url, HttpMethod.GET, null, correlationId);
    }

    /**
     * 取消电子书导入任务。
     *
     * @param ownerId 所有者标识
     * @param id 导入标识
     * @param correlationId 关联标识
     * @return 导入视图
     */
    public ImportView cancelImport(long ownerId, UUID id, String correlationId) {
        String url = ownerUrl(root() + "/api/v1/ebook-imports/" + id + "/cancel", ownerId);
        return exchangeImport(url, HttpMethod.POST, null, correlationId);
    }

    /**
     * 查询电子书目录。
     *
     * @param ownerId 所有者标识
     * @param id 导入标识
     * @param correlationId 关联标识
     * @return 目录视图
     */
    public CatalogView importCatalog(long ownerId, UUID id, String correlationId) {
        String url = ownerUrl(root() + "/api/v1/ebook-imports/" + id + "/catalog", ownerId);
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(null, correlationId), CatalogView.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    /**
     * 创建有声书生成运行，并由 Gateway 注入可信所有者标识。
     *
     * @param ownerId 所有者标识
     * @param request App 请求
     * @param correlationId 关联标识
     * @return 有声书生成运行
     */
    public AudiobookGenerationView createAudiobookGeneration(long ownerId, CreateAudiobookGeneration request,
                                                              String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("ebookAssetId", request.ebookAssetId());
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("mode", request.mode());
        payload.put("rightsConfirmed", request.rightsConfirmed());
        return exchange(root() + "/api/v1/audiobook-generations", HttpMethod.POST, payload,
                correlationId, AudiobookGenerationView.class);
    }

    /**
     * 为已完成有声书版本创建或复用一个异步 ZIP 导出任务。
     *
     * @param ownerId 所有者标识
     * @param generationId 已完成 generation 标识
     * @param request App 请求
     * @param correlationId 关联标识
     * @return 导出任务摘要
     */
    public AudiobookExportView createAudiobookExport(long ownerId, UUID generationId,
                                                      CreateAudiobookExport request, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("format", request.format());
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + generationId + "/exports", ownerId),
                HttpMethod.POST, payload, correlationId, AudiobookExportView.class);
    }

    /**
     * 为当前所有者创建一个仅替换单条音色绑定的有声书修订版本。
     *
     * @param ownerId 当前所有者标识
     * @param sourceGenerationId 已完成源版本标识
     * @param request App 修订请求
     * @param correlationId 关联标识
     * @return 修订版本摘要
     */
    public AudiobookGenerationView createAudiobookVoiceRevision(long ownerId, UUID sourceGenerationId,
                                                                 CreateAudiobookVoiceRevision request,
                                                                 String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("roleKey", request.roleKey());
        payload.put("provider", request.provider());
        payload.put("voiceType", request.voiceType());
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + sourceGenerationId
                        + "/voice-revisions", ownerId), HttpMethod.POST, payload, correlationId,
                AudiobookGenerationView.class);
    }

    /**
     * 转发当前主体一条低置信度说话人归因的不可变修订请求。
     *
     * @param ownerId 当前所有者标识
     * @param sourceGenerationId 已完成源版本标识
     * @param request 已经 Gateway 契约校验的修订请求
     * @param correlationId 关联标识
     * @return 已受理或复用的修订版本
     */
    public AudiobookGenerationView createAudiobookSpeakerRevision(long ownerId, UUID sourceGenerationId,
                                                                   CreateAudiobookSpeakerRevision request,
                                                                   String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("chapterIndex", request.chapterIndex());
        payload.put("sequenceNumber", request.sequenceNumber());
        payload.put("speakerKind", request.speakerKind());
        if (request.speakerCanonicalName() != null) {
            payload.put("speakerCanonicalName", request.speakerCanonicalName());
        }
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + sourceGenerationId
                        + "/speaker-revisions", ownerId), HttpMethod.POST, payload, correlationId,
                AudiobookGenerationView.class);
    }

    /**
     * 为当前所有者创建读音词典修订，并由 Reader 受限执行器确定最小重生成范围。
     *
     * @param ownerId 当前所有者标识
     * @param sourceGenerationId 已完成源版本标识
     * @param request 已校验的读音修订请求
     * @param correlationId 关联标识
     * @return 已受理或复用的修订版本
     */
    public AudiobookGenerationView createAudiobookPronunciationRevision(long ownerId, UUID sourceGenerationId,
                                                                         CreateAudiobookPronunciationRevision request,
                                                                         String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("term", request.term());
        payload.put("pinyin", request.pinyin());
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + sourceGenerationId
                        + "/pronunciation-revisions", ownerId), HttpMethod.POST, payload, correlationId,
                AudiobookGenerationView.class);
    }

    /**
     * 查询当前所有者的一条有声书生成运行。
     *
     * @param ownerId 所有者标识
     * @param id 生成运行标识
     * @param correlationId 关联标识
     * @return 有声书生成运行
     */
    public AudiobookGenerationView audiobookGeneration(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + id, ownerId), HttpMethod.GET,
                null, correlationId, AudiobookGenerationView.class);
    }

    /**
     * 查询当前所有者一个版本已冻结的读音词典。
     *
     * @param ownerId 当前所有者标识
     * @param generationId 有声书版本标识
     * @param correlationId 关联标识
     * @return 不含正文和密钥的词典视图
     */
    public AudiobookPronunciationDictionaryView audiobookPronunciationDictionary(long ownerId, UUID generationId,
                                                                                   String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + generationId + "/pronunciations",
                        ownerId), HttpMethod.GET, null, correlationId, AudiobookPronunciationDictionaryView.class);
    }

    /**
     * 请求取消当前所有者一条仍在处理的有声书生成运行。
     *
     * @param ownerId 所有者标识
     * @param id 生成运行标识
     * @param correlationId 关联标识
     * @return 取消已受理后的运行摘要
     */
    public AudiobookGenerationView cancelAudiobookGeneration(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + id + "/cancel", ownerId),
                HttpMethod.POST, null, correlationId, AudiobookGenerationView.class);
    }

    /**
     * 从当前所有者生成运行最后一个终态阶段重新提交任务。
     *
     * @param ownerId 所有者标识
     * @param id 生成运行标识
     * @param correlationId 关联标识
     * @return 重试已受理后的运行摘要
     */
    public AudiobookGenerationView retryAudiobookGeneration(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + id + "/retry", ownerId),
                HttpMethod.POST, null, correlationId, AudiobookGenerationView.class);
    }

    /**
     * 查询当前所有者一个 generation 下的异步 ZIP 导出任务。
     *
     * @param ownerId 所有者标识
     * @param generationId generation 标识
     * @param exportId 导出标识
     * @param correlationId 关联标识
     * @return 导出任务摘要
     */
    public AudiobookExportView audiobookExport(long ownerId, UUID generationId, UUID exportId,
                                               String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + generationId + "/exports/"
                + exportId, ownerId), HttpMethod.GET, null, correlationId, AudiobookExportView.class);
    }

    /**
     * 查询当前所有者的一份版本化章节播放清单。
     *
     * @param ownerId 所有者标识
     * @param id 生成运行标识
     * @param correlationId 关联标识
     * @return 不含存储位置的播放清单
     */
    public AudiobookPlaybackManifest audiobookPlaybackManifest(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + id + "/playback-manifest", ownerId),
                HttpMethod.GET, null, correlationId, AudiobookPlaybackManifest.class);
    }

    /**
     * 查询当前所有者可审核的全书人物、关系和说话人归因。
     *
     * @param ownerId 所有者标识
     * @param id 生成运行标识
     * @param correlationId 关联标识
     * @return 全书结构化分析视图
     */
    public AudiobookBookAnalysisView audiobookBookAnalysis(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + id + "/book-analysis", ownerId),
                HttpMethod.GET, null, correlationId, AudiobookBookAnalysisView.class);
    }

    /**
     * 查询当前所有者可审核的冻结音色计划。
     *
     * @param ownerId 所有者标识
     * @param id 生成运行标识
     * @param correlationId 关联标识
     * @return 冻结音色计划
     */
    public AudiobookVoicePlanView audiobookVoicePlan(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + id + "/voice-plan", ownerId),
                HttpMethod.GET, null, correlationId, AudiobookVoicePlanView.class);
    }

    /**
     * 查询当前所有者审核某个有声书版本时可选择的已启用音色目录。
     *
     * @param ownerId 当前所有者标识
     * @param id 有声书版本标识
     * @param correlationId 关联标识
     * @return 不含供应商密钥的音色目录
     */
    public AudiobookVoiceCatalogView audiobookVoiceCatalog(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/audiobook-generations/" + id + "/voice-catalog", ownerId),
                HttpMethod.GET, null, correlationId, AudiobookVoiceCatalogView.class);
    }

    /**
     * 将当前所有者一个已就绪的章节音频流式转发给 Gateway 响应。
     *
     * @param ownerId 所有者标识
     * @param generationId 生成运行标识
     * @param chapterIndex 章节序号
     * @param range 可选的客户端字节范围
     * @param response Gateway HTTP 响应
     * @param correlationId 关联标识
     */
    public void streamAudiobookChapter(long ownerId, UUID generationId, int chapterIndex, String range,
                                       HttpServletResponse response, String correlationId) {
        HttpURLConnection connection = null;
        try {
            URI url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/audiobook-generations/" + generationId
                            + "/chapters/" + chapterIndex + "/audio")
                    .queryParam("ownerId", ownerId).build().encode().toUri();
            connection = (HttpURLConnection) url.toURL().openConnection();
            if (properties.readerToken() == null || properties.readerToken().isBlank()) {
                throw new GatewayUnauthorizedException();
            }
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + properties.readerToken());
            connection.setRequestProperty("X-Correlation-Id", correlationId);
            connection.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, "identity");
            if (range != null && !range.isBlank()) {
                connection.setRequestProperty(HttpHeaders.RANGE, range);
            }
            connection.setConnectTimeout(properties.connectTimeoutMillis());
            connection.setReadTimeout(Math.max(properties.readTimeoutMillis(), 120_000));
            int status = connection.getResponseCode();
            response.setStatus(status);
            copyHeader(connection, response, HttpHeaders.CONTENT_TYPE);
            copyHeader(connection, response, HttpHeaders.CONTENT_LENGTH);
            copyHeader(connection, response, HttpHeaders.CONTENT_RANGE);
            copyHeader(connection, response, HttpHeaders.ACCEPT_RANGES);
            if (status < 200 || status >= 300) {
                return;
            }
            try (var input = connection.getInputStream(); var output = response.getOutputStream()) {
                input.transferTo(output);
            }
        } catch (GatewayUnauthorizedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GatewayDownstreamException();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 将当前所有者一条已审核音色样音流式转发给 Gateway 响应。
     *
     * @param ownerId 所有者标识
     * @param generationId 生成运行标识
     * @param provider 音色供应商标识
     * @param voiceType 音色标识
     * @param range 可选的客户端字节范围
     * @param response Gateway HTTP 响应
     * @param correlationId 关联标识
     */
    public void streamAudiobookVoicePreview(long ownerId, UUID generationId, String provider, String voiceType,
                                            String range, HttpServletResponse response, String correlationId) {
        HttpURLConnection connection = null;
        try {
            URI url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/audiobook-generations/" + generationId
                            + "/voice-preview/audio")
                    .queryParam("ownerId", ownerId).queryParam("provider", provider)
                    .queryParam("voiceType", voiceType).build().encode().toUri();
            connection = (HttpURLConnection) url.toURL().openConnection();
            if (properties.readerToken() == null || properties.readerToken().isBlank()) {
                throw new GatewayUnauthorizedException();
            }
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + properties.readerToken());
            connection.setRequestProperty("X-Correlation-Id", correlationId);
            connection.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, "identity");
            if (range != null && !range.isBlank()) {
                connection.setRequestProperty(HttpHeaders.RANGE, range);
            }
            connection.setConnectTimeout(properties.connectTimeoutMillis());
            connection.setReadTimeout(Math.max(properties.readTimeoutMillis(), 120_000));
            int status = connection.getResponseCode();
            response.setStatus(status);
            copyHeader(connection, response, HttpHeaders.CONTENT_TYPE);
            copyHeader(connection, response, HttpHeaders.CONTENT_LENGTH);
            copyHeader(connection, response, HttpHeaders.CONTENT_RANGE);
            copyHeader(connection, response, HttpHeaders.ACCEPT_RANGES);
            if (status < 200 || status >= 300) {
                return;
            }
            try (var input = connection.getInputStream(); var output = response.getOutputStream()) {
                input.transferTo(output);
            }
        } catch (GatewayUnauthorizedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GatewayDownstreamException();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 将当前所有者一个已完成的整书 ZIP 导出归档流式转发给 Gateway 响应。
     *
     * @param ownerId 所有者标识
     * @param generationId generation 标识
     * @param exportId 导出标识
     * @param response Gateway HTTP 响应
     * @param correlationId 关联标识
     */
    public void streamAudiobookExport(long ownerId, UUID generationId, UUID exportId,
                                      HttpServletResponse response, String correlationId) {
        HttpURLConnection connection = null;
        try {
            URI url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/audiobook-generations/" + generationId
                            + "/exports/" + exportId + "/archive")
                    .queryParam("ownerId", ownerId).build().encode().toUri();
            connection = (HttpURLConnection) url.toURL().openConnection();
            if (properties.readerToken() == null || properties.readerToken().isBlank()) {
                throw new GatewayUnauthorizedException();
            }
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + properties.readerToken());
            connection.setRequestProperty("X-Correlation-Id", correlationId);
            connection.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, "identity");
            connection.setConnectTimeout(properties.connectTimeoutMillis());
            connection.setReadTimeout(Math.max(properties.readTimeoutMillis(), 120_000));
            int status = connection.getResponseCode();
            response.setStatus(status);
            copyHeader(connection, response, HttpHeaders.CONTENT_TYPE);
            copyHeader(connection, response, HttpHeaders.CONTENT_LENGTH);
            copyHeader(connection, response, HttpHeaders.CONTENT_DISPOSITION);
            copyHeader(connection, response, HttpHeaders.CACHE_CONTROL);
            if (status < 200 || status >= 300) {
                return;
            }
            try (var input = connection.getInputStream(); var output = response.getOutputStream()) {
                input.transferTo(output);
            }
        } catch (GatewayUnauthorizedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GatewayDownstreamException();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 创建书源发现任务。
     *
     * @param ownerId 所有者标识
     * @param request 创建请求
     * @param correlationId 关联标识
     * @return 发现视图
     */
    public DiscoveryView createDiscovery(long ownerId, CreateDiscovery request, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("url", request.url());
        return exchange(root() + "/api/v1/source-discoveries", HttpMethod.POST, payload,
                correlationId, DiscoveryView.class);
    }

    /**
     * 查询书源发现任务。
     *
     * @param ownerId 所有者标识
     * @param id 发现标识
     * @param correlationId 关联标识
     * @return 发现视图
     */
    public DiscoveryView discovery(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/source-discoveries/" + id, ownerId), HttpMethod.GET,
                null, correlationId, DiscoveryView.class);
    }

    /**
     * 取消书源发现任务。
     *
     * @param ownerId 所有者标识
     * @param id 发现标识
     * @param correlationId 关联标识
     * @return 发现视图
     */
    public DiscoveryView cancelDiscovery(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/source-discoveries/" + id + "/cancel", ownerId),
                HttpMethod.POST, null, correlationId, DiscoveryView.class);
    }

    /**
     * 创建书源健康检查任务。
     *
     * @param ownerId 所有者标识
     * @param request 创建请求
     * @param correlationId 关联标识
     * @return 健康检查视图
     */
    public HealthCheckView createHealthCheck(long ownerId, CreateHealthCheck request, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("keyword", request.keyword());
        return exchange(root() + "/api/v1/source-health-checks", HttpMethod.POST, payload,
                correlationId, HealthCheckView.class);
    }

    /**
     * 查询书源健康检查任务。
     *
     * @param ownerId 所有者标识
     * @param id 健康检查标识
     * @param correlationId 关联标识
     * @return 健康检查视图
     */
    public HealthCheckView healthCheck(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/source-health-checks/" + id, ownerId), HttpMethod.GET,
                null, correlationId, HealthCheckView.class);
    }

    /**
     * 取消书源健康检查任务。
     *
     * @param ownerId 所有者标识
     * @param id 健康检查标识
     * @param correlationId 关联标识
     * @return 健康检查视图
     */
    public HealthCheckView cancelHealthCheck(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/source-health-checks/" + id + "/cancel", ownerId),
                HttpMethod.POST, null, correlationId, HealthCheckView.class);
    }

    /**
     * 创建章节预取任务。
     *
     * @param ownerId 所有者标识
     * @param request 创建请求
     * @param correlationId 关联标识
     * @return 预取任务视图
     */
    public PrefetchView createPrefetch(long ownerId, CreatePrefetch request, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("sourceId", request.sourceId());
        payload.put("bookUrl", request.bookUrl());
        payload.put("chapterIndexes", request.chapterIndexes());
        return exchange(root() + "/api/v1/chapter-prefetches", HttpMethod.POST, payload,
                correlationId, PrefetchView.class);
    }

    /**
     * 查询章节预取任务。
     *
     * @param ownerId 所有者标识
     * @param id 预取标识
     * @param correlationId 关联标识
     * @return 预取任务视图
     */
    public PrefetchView prefetch(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/chapter-prefetches/" + id, ownerId), HttpMethod.GET,
                null, correlationId, PrefetchView.class);
    }

    /**
     * 取消章节预取任务。
     *
     * @param ownerId 所有者标识
     * @param id 预取标识
     * @param correlationId 关联标识
     * @return 预取任务视图
     */
    public PrefetchView cancelPrefetch(long ownerId, UUID id, String correlationId) {
        return exchange(ownerUrl(root() + "/api/v1/chapter-prefetches/" + id + "/cancel", ownerId),
                HttpMethod.POST, null, correlationId, PrefetchView.class);
    }

    /**
     * 查询章节缓存。
     *
     * @param ownerId 所有者标识
     * @param sourceId 书源标识
     * @param bookUrl 图书地址
     * @param chapterUrl 章节地址
     * @param correlationId 关联标识
     * @return 缓存视图
     */
    public CacheView chapterCache(long ownerId, UUID sourceId, String bookUrl, String chapterUrl,
                                  String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/chapter-cache")
                .queryParam("ownerId", ownerId).queryParam("sourceId", sourceId)
                .queryParam("bookUrl", bookUrl).queryParam("chapterUrl", chapterUrl).toUriString();
        return exchange(url, HttpMethod.GET, null, correlationId, CacheView.class);
    }

    private SearchView exchangeSearch(String url, HttpMethod method, Object body, String correlationId) {
        var response = restTemplate.exchange(url, method, entity(body, correlationId), InternalSearchView.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        var value = response.getBody();
        return new SearchView(value.id(), value.status(), value.keyword(), value.mode(), value.page(),
                value.completedShards(), value.failedShards(), value.totalShards(), value.results(),
                value.createdAt(), value.updatedAt());
    }

    private Map<String, Object> runtime(String operation, Map<String, Object> payload, String correlationId) {
        var response = restTemplate.exchange(root() + "/api/v1/source-runtime/" + operation,
                HttpMethod.POST, entity(payload, correlationId),
                new ParameterizedTypeReference<Map<String, Object>>() { });
        if (response.getBody() == null) throw new IllegalStateException("Reader Service returned an empty response");
        return response.getBody();
    }

    /** Reader 内部搜索响应，调度任务标识不会投影到 App 契约。 */
    private record InternalSearchView(UUID id, UUID taskId, String status, String keyword, String mode, int page,
                                      int completedShards, int failedShards, int totalShards,
                                      java.util.List<java.util.Map<String, Object>> results,
                                      java.time.Instant createdAt, java.time.Instant updatedAt) { }

    private ImportView exchangeImport(String url, HttpMethod method, Object body, String correlationId) {
        var response = restTemplate.exchange(url, method, entity(body, correlationId), ImportView.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    private String ownerUrl(String url, long ownerId) {
        return UriComponentsBuilder.fromHttpUrl(url).queryParam("ownerId", ownerId).toUriString();
    }

    private <T> T exchange(String url, HttpMethod method, Object body, String correlationId, Class<T> type) {
        try {
            var response = restTemplate.exchange(url, method, entity(body, correlationId), type);
            if (response.getBody() == null) {
                throw new IllegalStateException("Reader Service returned an empty response");
            }
            return response.getBody();
        } catch (HttpClientErrorException exception) {
            throw readerRejection(exception);
        }
    }

    private RuntimeException readerRejection(HttpClientErrorException exception) {
        Matcher matcher = READER_ERROR_CODE.matcher(exception.getResponseBodyAsString());
        if (!matcher.find()) {
            return new GatewayDownstreamException();
        }
        // 只把格式受限的 Reader 业务错误码透传给 App，避免下游错误正文进入公共接口。
        return new GatewayReaderRejectedException(exception.getStatusCode(), matcher.group(1));
    }

    private HttpEntity<?> entity(Object body, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        if (properties.readerToken() == null || properties.readerToken().isBlank()) {
            throw new GatewayUnauthorizedException();
        }
        headers.setBearerAuth(properties.readerToken());
        headers.set("X-Correlation-Id", correlationId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private void copyHeader(HttpURLConnection connection, HttpServletResponse response, String name) {
        String value = connection.getHeaderField(name);
        if (value != null && !value.isBlank()) {
            response.setHeader(name, value);
        }
    }

    private String root() {
        return properties.readerUrl().replaceAll("/+$", "");
    }
}
