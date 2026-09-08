package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.AudiobookTextProjectionBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookTextProjectionInput;
import com.yuyutian.mytools.reader.model.AudiobookTextProjectionResult;
import com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookSynthesisInput;
import com.yuyutian.mytools.reader.model.AudiobookSynthesisResult;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisInput;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisResult;
import com.yuyutian.mytools.reader.model.AudiobookVoiceMatchInput;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanRequest;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanResult;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationPreparationInput;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationPreparationResult;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationAffectedChaptersRequest;
import com.yuyutian.mytools.reader.service.AudiobookGenerationService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 执行器读取和回写有声书正文及音频产物的内部接口。
 */
@RestController
@RequestMapping("/api/internal/v1/audiobook-generations")
public class AudiobookTextProjectionInternalController {

    private final AudiobookGenerationService service;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建正文快照内部控制器。
     *
     * @param service 有声书生成服务
     * @param authorizer 内部请求校验器
     */
    public AudiobookTextProjectionInternalController(AudiobookGenerationService service,
                                                     InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /**
     * 返回冻结的章节定位信息。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @return 正文投影输入
     */
    @GetMapping("/{id}/text-projection-input")
    public AudiobookTextProjectionInput input(@RequestHeader("Authorization") String authorization,
                                              @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.textProjectionInput(id);
    }

    /**
     * 保存一个正文快照批次。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param request 正文快照批次
     * @return 写入数量
     */
    @PostMapping("/{id}/text-projection-chapters")
    public Map<String, Integer> save(@RequestHeader("Authorization") String authorization,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody AudiobookTextProjectionBatchRequest request) {
        authorizer.requireAuthorized(authorization);
        return Map.of("saved", service.saveTextProjectionBatch(id, request));
    }

    /**
     * 完成整书正文快照冻结。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @return 正文冻结结果
     */
    @PostMapping("/{id}/complete-text-projection")
    public AudiobookTextProjectionResult complete(@RequestHeader("Authorization") String authorization,
                                                  @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.completeTextProjection(id);
    }

    /**
     * 返回已冻结章节正文的合成输入。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @return 章节合成输入
     */
    @GetMapping("/{id}/synthesis-input")
    public AudiobookSynthesisInput synthesisInput(@RequestHeader("Authorization") String authorization,
                                                  @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.synthesisInput(id);
    }

    /**
     * 返回读音修订执行器扫描精确匹配章节所需的冻结输入。
     *
     * @param authorization 内部授权头
     * @param id 读音修订版本标识
     * @return 需匹配词条与冻结章节
     */
    @GetMapping("/{id}/pronunciation-preparation-input")
    public AudiobookPronunciationPreparationInput pronunciationPreparationInput(
            @RequestHeader("Authorization") String authorization, @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.pronunciationPreparationInput(id);
    }

    /**
     * 回写读音词条精确命中的章节，并推进最小章节重生成。
     *
     * @param authorization 内部授权头
     * @param id 读音修订版本标识
     * @param request 受限执行器回写的命中章节集合
     * @return 已重新排队的章节数
     */
    @PostMapping("/{id}/pronunciation-affected-chapters")
    public AudiobookPronunciationPreparationResult completePronunciationPreparation(
            @RequestHeader("Authorization") String authorization, @PathVariable UUID id,
            @Valid @RequestBody AudiobookPronunciationAffectedChaptersRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.completePronunciationPreparation(id, request.chapterIndexes());
    }

    /**
     * 返回已冻结的全书分析输入。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @return 全书分析输入
     */
    @GetMapping("/{id}/book-analysis-input")
    public AudiobookBookAnalysisInput bookAnalysisInput(@RequestHeader("Authorization") String authorization,
                                                        @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.bookAnalysisInput(id);
    }

    /**
     * 保存一次已归并的全书结构化分析结果。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param request 全书分析结果
     * @return 已保存实体数量摘要
     */
    @PostMapping("/{id}/book-analysis")
    public AudiobookBookAnalysisResult saveBookAnalysis(@RequestHeader("Authorization") String authorization,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody AudiobookBookAnalysisBatchRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.saveBookAnalysis(id, request);
    }

    /**
     * 完成全书分析并推进后续合成流程。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @return 已保存实体数量摘要
     */
    @PostMapping("/{id}/complete-book-analysis")
    public AudiobookBookAnalysisResult completeBookAnalysis(@RequestHeader("Authorization") String authorization,
                                                            @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.completeBookAnalysis(id);
    }

    /**
     * 返回已分析人物和启用目录的音色匹配输入。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @return 音色匹配输入
     */
    @GetMapping("/{id}/voice-match-input")
    public AudiobookVoiceMatchInput voiceMatchInput(@RequestHeader("Authorization") String authorization,
                                                    @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.voiceMatchInput(id);
    }

    /**
     * 保存一次已冻结的全书音色绑定计划。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param request 音色目录与绑定计划
     * @return 已保存绑定数量摘要
     */
    @PostMapping("/{id}/voice-plan")
    public AudiobookVoicePlanResult saveVoicePlan(@RequestHeader("Authorization") String authorization,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody AudiobookVoicePlanRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.saveVoicePlan(id, request);
    }

    /**
     * 完成音色计划并推进章节合成流程。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @return 已冻结绑定数量摘要
     */
    @PostMapping("/{id}/complete-voice-plan")
    public AudiobookVoicePlanResult completeVoicePlan(@RequestHeader("Authorization") String authorization,
                                                      @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.completeVoicePlan(id);
    }

    /**
     * 保存一批章节音频资产。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @param request 章节音频批次
     * @return 写入数量
     */
    @PostMapping("/{id}/synthesized-chapters")
    public Map<String, Integer> saveSynthesis(@RequestHeader("Authorization") String authorization,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody AudiobookSynthesisBatchRequest request) {
        authorizer.requireAuthorized(authorization);
        return Map.of("saved", service.saveSynthesisBatch(id, request));
    }

    /**
     * 完成整书章节音频合成。
     *
     * @param authorization 内部授权头
     * @param id 生成运行标识
     * @return 音频合成结果
     */
    @PostMapping("/{id}/complete-synthesis")
    public AudiobookSynthesisResult completeSynthesis(@RequestHeader("Authorization") String authorization,
                                                      @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.completeSynthesis(id);
    }
}
