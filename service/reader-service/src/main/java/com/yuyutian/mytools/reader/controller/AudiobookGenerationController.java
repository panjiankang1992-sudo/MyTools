package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.AudiobookGenerationView;
import com.yuyutian.mytools.reader.model.AudiobookPlaybackManifest;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisView;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanView;
import com.yuyutian.mytools.reader.model.AudiobookVoiceCatalogView;
import com.yuyutian.mytools.reader.model.AudiobookExportView;
import com.yuyutian.mytools.reader.model.CreateAudiobookGenerationRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookVoiceRevisionRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookSpeakerRevisionRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookPronunciationRevisionRequest;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationDictionaryView;
import com.yuyutian.mytools.reader.model.CreateAudiobookExportRequest;
import com.yuyutian.mytools.reader.service.AudiobookGenerationService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 有声书生成运行的内部编排接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/audiobook-generations")
public class AudiobookGenerationController {

    private final AudiobookGenerationService service;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建有声书生成控制器。
     *
     * @param service 有声书生成服务
     * @param authorizer 内部请求授权校验器
     */
    public AudiobookGenerationController(AudiobookGenerationService service, InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /**
     * 创建或复用有声书生成运行。
     *
     * @param authorization 内部授权头
     * @param request 创建请求
     * @return 已受理运行
     */
    @PostMapping
    public ResponseEntity<AudiobookGenerationView> create(@RequestHeader("Authorization") String authorization,
                                                            @Valid @RequestBody CreateAudiobookGenerationRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.create(request));
    }

    /**
     * 查询属于指定用户的生成运行。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 生成运行视图
     */
    @GetMapping("/{id}")
    public AudiobookGenerationView get(@RequestHeader("Authorization") String authorization,
                                       @PathVariable UUID id, @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return service.get(id, ownerId);
    }

    /**
     * 请求取消指定所有者当前正在处理的有声书阶段。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 取消已受理后的运行视图
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<AudiobookGenerationView> cancel(@RequestHeader("Authorization") String authorization,
                                                           @PathVariable UUID id,
                                                           @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.cancel(id, ownerId));
    }

    /**
     * 从最后一个失败、超时或已取消阶段重新提交有声书处理。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 重试已受理后的运行视图
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<AudiobookGenerationView> retry(@RequestHeader("Authorization") String authorization,
                                                          @PathVariable UUID id,
                                                          @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.retry(id, ownerId));
    }

    /**
     * 查询一个生成版本的章节播放清单。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 不含存储地址的播放清单
     */
    @GetMapping("/{id}/playback-manifest")
    public AudiobookPlaybackManifest playbackManifest(@RequestHeader("Authorization") String authorization,
                                                      @PathVariable UUID id, @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return service.playbackManifest(id, ownerId);
    }

    /**
     * 查询一代有声书可审核的全书人物、关系与说话人归因结果。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 全书结构化分析视图
     */
    @GetMapping("/{id}/book-analysis")
    public AudiobookBookAnalysisView bookAnalysis(@RequestHeader("Authorization") String authorization,
                                                  @PathVariable UUID id, @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return service.bookAnalysis(id, ownerId);
    }

    /**
     * 查询一代有声书可审核的冻结旁白和角色音色计划。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 冻结音色计划
     */
    @GetMapping("/{id}/voice-plan")
    public AudiobookVoicePlanView voicePlan(@RequestHeader("Authorization") String authorization,
                                            @PathVariable UUID id, @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return service.voicePlan(id, ownerId);
    }

    /**
     * 查询人工审核可选择的已启用音色目录，不返回供应商认证信息。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 已启用音色目录
     */
    @GetMapping("/{id}/voice-catalog")
    public AudiobookVoiceCatalogView voiceCatalog(@RequestHeader("Authorization") String authorization,
                                                  @PathVariable UUID id, @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return service.voiceCatalog(id, ownerId);
    }

    /**
     * 查询一代有声书已冻结的读音词典，不返回正文或供应商认证信息。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 已冻结读音词典
     */
    @GetMapping("/{id}/pronunciations")
    public AudiobookPronunciationDictionaryView pronunciations(@RequestHeader("Authorization") String authorization,
                                                                @PathVariable UUID id,
                                                                @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return service.pronunciationDictionary(id, ownerId);
    }

    /**
     * 用已启用目录中的一个音色创建新的修订版本，仅重新合成受影响章节。
     *
     * @param authorization 内部授权头
     * @param id 已完成源版本标识
     * @param ownerId 所有者标识
     * @param request 人工音色替换请求
     * @return 已受理修订版本
     */
    @PostMapping("/{id}/voice-revisions")
    public ResponseEntity<AudiobookGenerationView> createVoiceRevision(
            @RequestHeader("Authorization") String authorization, @PathVariable UUID id,
            @RequestParam @NotNull Long ownerId, @Valid @RequestBody CreateAudiobookVoiceRevisionRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.createVoiceRevision(id, ownerId, request));
    }

    /**
     * 用人工确认的一条低置信度说话人归因创建新的不可变版本，仅重新合成所属章节。
     *
     * @param authorization 内部授权头
     * @param id 已完成源版本标识
     * @param ownerId 所有者标识
     * @param request 人工说话人归因修订请求
     * @return 已受理修订版本
     */
    @PostMapping("/{id}/speaker-revisions")
    public ResponseEntity<AudiobookGenerationView> createSpeakerRevision(
            @RequestHeader("Authorization") String authorization, @PathVariable UUID id,
            @RequestParam @NotNull Long ownerId, @Valid @RequestBody CreateAudiobookSpeakerRevisionRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.createSpeakerRevision(id, ownerId, request));
    }

    /**
     * 用人工确认的中文词条读法创建不可变版本，并仅重新合成精确命中的章节。
     *
     * @param authorization 内部授权头
     * @param id 已完成源版本标识
     * @param ownerId 所有者标识
     * @param request 人工读音修订请求
     * @return 已受理修订版本
     */
    @PostMapping("/{id}/pronunciation-revisions")
    public ResponseEntity<AudiobookGenerationView> createPronunciationRevision(
            @RequestHeader("Authorization") String authorization, @PathVariable UUID id,
            @RequestParam @NotNull Long ownerId,
            @Valid @RequestBody CreateAudiobookPronunciationRevisionRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.createPronunciationRevision(id, ownerId, request));
    }

    /**
     * 为一个已完成 generation 创建或复用异步 ZIP 导出任务。
     *
     * @param authorization 内部授权头
     * @param id 已完成 generation 标识
     * @param ownerId 所有者标识
     * @param request 导出请求
     * @return 已受理导出任务
     */
    @PostMapping("/{id}/exports")
    public ResponseEntity<AudiobookExportView> createExport(@RequestHeader("Authorization") String authorization,
                                                            @PathVariable UUID id, @RequestParam @NotNull Long ownerId,
                                                            @Valid @RequestBody CreateAudiobookExportRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.createExport(id, ownerId, request));
    }

    /**
     * 查询一个 generation 下属于指定所有者的导出任务。
     *
     * @param authorization 内部授权头
     * @param id generation 标识
     * @param exportId 导出标识
     * @param ownerId 所有者标识
     * @return 导出任务摘要
     */
    @GetMapping("/{id}/exports/{exportId}")
    public AudiobookExportView export(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
                                      @PathVariable UUID exportId, @RequestParam @NotNull Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return service.getExport(id, exportId, ownerId);
    }

    /**
     * 流式读取一个已完成的 ZIP 导出归档，仅允许内部网关调用。
     *
     * @param authorization 内部授权头
     * @param id generation 标识
     * @param exportId 导出标识
     * @param ownerId 所有者标识
     * @param response HTTP 响应
     */
    @GetMapping("/{id}/exports/{exportId}/archive")
    public void exportArchive(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
                              @PathVariable UUID exportId, @RequestParam @NotNull Long ownerId,
                              HttpServletResponse response) {
        authorizer.requireAuthorized(authorization);
        service.streamExport(id, exportId, ownerId, response);
    }

    /**
     * 以单段 Range 语义读取一个已就绪章节音频，仅允许内部网关调用。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param chapterIndex 章节序号
     * @param ownerId 所有者标识
     * @param range 可选的字节范围
     * @param response HTTP 响应
     */
    @GetMapping("/{id}/chapters/{chapterIndex}/audio")
    public void chapterAudio(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
                             @PathVariable int chapterIndex, @RequestParam @NotNull Long ownerId,
                             @RequestHeader(value = "Range", required = false) String range,
                             HttpServletResponse response) {
        authorizer.requireAuthorized(authorization);
        service.streamChapterAudio(id, ownerId, chapterIndex, range, response);
    }

    /**
     * 以单段 Range 语义读取一段已审核音色样音，仅允许内部网关调用。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @param provider 音色供应商标识
     * @param voiceType 音色标识
     * @param range 可选的字节范围
     * @param response HTTP 响应
     */
    @GetMapping("/{id}/voice-preview/audio")
    public void voicePreviewAudio(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
                                  @RequestParam @NotNull Long ownerId,
                                  @RequestParam @NotBlank @Size(max = 64) String provider,
                                  @RequestParam @NotBlank @Size(max = 256) String voiceType,
                                  @RequestHeader(value = "Range", required = false) String range,
                                  HttpServletResponse response) {
        authorizer.requireAuthorized(authorization);
        service.streamVoicePreview(id, ownerId, provider, voiceType, range, response);
    }
}
