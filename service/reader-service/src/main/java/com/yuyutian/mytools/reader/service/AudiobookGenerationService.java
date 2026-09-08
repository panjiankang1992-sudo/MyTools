package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.config.AudiobookGenerationMetrics;
import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.model.AudiobookGenerationRecord;
import com.yuyutian.mytools.reader.model.AudiobookGenerationView;
import com.yuyutian.mytools.reader.model.AudiobookAudioChapter;
import com.yuyutian.mytools.reader.model.AudiobookVoicePreview;
import com.yuyutian.mytools.reader.model.AudiobookExportArchive;
import com.yuyutian.mytools.reader.model.AudiobookExportInput;
import com.yuyutian.mytools.reader.model.AudiobookExportRecord;
import com.yuyutian.mytools.reader.model.AudiobookExportResult;
import com.yuyutian.mytools.reader.model.AudiobookExportView;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisInput;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisResult;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisView;
import com.yuyutian.mytools.reader.model.AudiobookVoiceMatchInput;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanRequest;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanResult;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanView;
import com.yuyutian.mytools.reader.model.AudiobookVoiceCatalogView;
import com.yuyutian.mytools.reader.model.CreateAudiobookVoiceRevisionRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookSpeakerRevisionRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookPronunciationRevisionRequest;
import com.yuyutian.mytools.reader.model.AudiobookPlaybackManifest;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationDictionaryView;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationPreparationInput;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationPreparationResult;
import com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookSynthesisInput;
import com.yuyutian.mytools.reader.model.AudiobookSynthesisResult;
import com.yuyutian.mytools.reader.model.AudiobookTextProjectionBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookTextProjectionInput;
import com.yuyutian.mytools.reader.model.AudiobookTextProjectionResult;
import com.yuyutian.mytools.reader.model.CreateAudiobookGenerationRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookExportRequest;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.repository.AudiobookGenerationRepository;
import com.yuyutian.mytools.reader.repository.AudiobookExportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 有声书生成运行的创建和查询服务。
 */
@Service
public class AudiobookGenerationService {

    private final AudiobookGenerationRepository repository;
    private final TaskSchedulerClient schedulerClient;
    private final ReaderProperties properties;
    private final AudiobookAudioStreamClient audioStreamClient;
    private final AudiobookRevisionPlanner revisionPlanner;
    private final AudiobookExportRepository exportRepository;
    private final AudiobookExportStreamClient exportStreamClient;
    private final AudiobookGenerationMetrics metrics;
    private final AudiobookGenerationAvailability availability;

    /**
     * 创建有声书生成服务。
     *
     * @param repository 有声书运行仓储
     * @param schedulerClient 调度客户端
     * @param properties 阅读服务配置
     * @param audioStreamClient 有声书音频流客户端
     * @param revisionPlanner 增量章节复用规划器
     * @param exportRepository 有声书导出仓储
     * @param exportStreamClient 有声书归档流客户端
     * @param metrics 有声书运行指标记录器
     * @param availability 有声书新工作灰度策略
     */
    public AudiobookGenerationService(AudiobookGenerationRepository repository, TaskSchedulerClient schedulerClient,
                                      ReaderProperties properties, AudiobookAudioStreamClient audioStreamClient,
                                      AudiobookRevisionPlanner revisionPlanner, AudiobookExportRepository exportRepository,
                                      AudiobookExportStreamClient exportStreamClient,
                                      AudiobookGenerationMetrics metrics, AudiobookGenerationAvailability availability) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
        this.properties = properties;
        this.audioStreamClient = audioStreamClient;
        this.revisionPlanner = revisionPlanner;
        this.exportRepository = exportRepository;
        this.exportStreamClient = exportStreamClient;
        this.metrics = metrics;
        this.availability = availability;
    }

    /**
     * 幂等创建一条有声书生成运行、冻结章节清单并提交正文投影任务。
     *
     * @param request 创建请求
     * @return 创建或复用的运行视图
     */
    @Transactional
    public AudiobookGenerationView create(CreateAudiobookGenerationRequest request) {
        if (!request.rightsConfirmed()) {
            throw new IllegalArgumentException("audiobook generation rights were not confirmed");
        }
        var existing = repository.findByIdempotencyKey(request.ownerId(), request.idempotencyKey());
        if (existing.isEmpty()) {
            availability.requireNewWorkAllowed(request.ownerId());
        }
        AudiobookGenerationRecord record = existing.orElseGet(() -> createRecord(request));
        if (!record.ebookAssetId().equals(request.ebookAssetId()) || record.mode() != request.mode()) {
            throw new IllegalArgumentException("audiobook generation idempotency conflict");
        }
        if (record.taskId() == null) {
            availability.requireNewWorkAllowed(record.ownerId());
            UUID taskId = schedulerClient.createTask("reader_generate_audiobook",
                    "reader_generate_audiobook:" + record.id() + ":v" + record.generationVersion(),
                    "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                    "generationId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            repository.bindTextProjectionTask(record.id(), taskId);
            record = required(record.id());
        }
        if (existing.isEmpty()) {
            metrics.recordAccepted(record.mode());
        }
        return view(record);
    }

    /**
     * 用已启用目录中的一个音色替换完成版本的旁白或角色，并只合成受影响章节。
     *
     * @param sourceId 已完成的源生成版本标识
     * @param ownerId 当前所有者标识
     * @param request 修订请求
     * @return 新建或复用的修订版本
     */
    @Transactional(noRollbackFor = AudiobookDailyCharacterQuotaExceededException.class)
    public AudiobookGenerationView createVoiceRevision(UUID sourceId, long ownerId,
                                                        CreateAudiobookVoiceRevisionRequest request) {
        AudiobookGenerationRecord source = required(sourceId);
        if (source.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(sourceId);
        }
        if (!"COMPLETED".equals(source.status())) {
            throw new IllegalStateException("audiobook voice revision requires a completed generation");
        }
        var existing = repository.findByIdempotencyKey(ownerId, request.idempotencyKey());
        if (existing.isEmpty()) {
            availability.requireNewWorkAllowed(ownerId);
        }
        AudiobookGenerationRecord record = existing.orElseGet(() -> {
                    UUID revisionId = UUID.randomUUID();
                    int version = repository.nextGenerationVersion(ownerId, source.bookLineageKey());
                    repository.createVoiceRevision(source, revisionId, version, request);
                    return required(revisionId);
                });
        if (!repository.matchesVoiceRevision(record.id(), sourceId, request)) {
            throw new IllegalArgumentException("audiobook voice revision idempotency conflict");
        }
        if (!"COMPLETED".equals(record.status()) && record.synthesisTaskId() == null) {
            availability.requireNewWorkAllowed(ownerId);
            reserveSynthesisQuota(record, "VOICE_REPAIR");
            UUID taskId = schedulerClient.createTask("reader_synthesize_audiobook",
                    "reader_synthesize_audiobook:" + record.id() + ":v" + record.generationVersion(),
                    "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                    "generationId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            repository.bindSynthesisTask(record.id(), taskId);
            record = required(record.id());
        }
        if (existing.isEmpty()) {
            metrics.recordAccepted(record.mode());
        }
        return view(record);
    }

    /**
     * 用人工确认的低置信度说话人归因创建不可变修订版本，并仅重新合成所在章节。
     *
     * @param sourceId 已完成的源生成版本标识
     * @param ownerId 当前所有者标识
     * @param request 人工说话人归因修订请求
     * @return 新建或复用的修订版本
     */
    @Transactional(noRollbackFor = AudiobookDailyCharacterQuotaExceededException.class)
    public AudiobookGenerationView createSpeakerRevision(UUID sourceId, long ownerId,
                                                          CreateAudiobookSpeakerRevisionRequest request) {
        AudiobookGenerationRecord source = required(sourceId);
        if (source.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(sourceId);
        }
        if (!"COMPLETED".equals(source.status())) {
            throw new IllegalStateException("audiobook speaker revision requires a completed generation");
        }
        var existing = repository.findByIdempotencyKey(ownerId, request.idempotencyKey());
        if (existing.isEmpty()) {
            availability.requireNewWorkAllowed(ownerId);
        }
        AudiobookGenerationRecord record = existing.orElseGet(() -> {
            UUID revisionId = UUID.randomUUID();
            int version = repository.nextGenerationVersion(ownerId, source.bookLineageKey());
            repository.createSpeakerRevision(source, revisionId, version, request);
            return required(revisionId);
        });
        if (!repository.matchesSpeakerRevision(record.id(), sourceId, request)) {
            throw new IllegalArgumentException("audiobook speaker revision idempotency conflict");
        }
        if (!"COMPLETED".equals(record.status()) && record.synthesisTaskId() == null) {
            availability.requireNewWorkAllowed(ownerId);
            reserveSynthesisQuota(record, "SPEAKER_REPAIR");
            UUID taskId = schedulerClient.createTask("reader_synthesize_audiobook",
                    "reader_synthesize_audiobook:" + record.id() + ":v" + record.generationVersion(),
                    "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                    "generationId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            repository.bindSynthesisTask(record.id(), taskId);
            record = required(record.id());
        }
        if (existing.isEmpty()) {
            metrics.recordAccepted(record.mode());
        }
        return view(record);
    }

    /**
     * 用人工确认的中文读音创建不可变子版本，并由受限执行器只找出需要重新合成的章节。
     *
     * @param sourceId 已完成的源生成版本标识
     * @param ownerId 当前所有者标识
     * @param request 人工读音修订请求
     * @return 新建或复用的读音修订版本
     */
    @Transactional
    public AudiobookGenerationView createPronunciationRevision(UUID sourceId, long ownerId,
                                                                CreateAudiobookPronunciationRevisionRequest request) {
        AudiobookGenerationRecord source = required(sourceId);
        if (source.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(sourceId);
        }
        if (!"COMPLETED".equals(source.status())) {
            throw new IllegalStateException("audiobook pronunciation revision requires a completed generation");
        }
        var existing = repository.findByIdempotencyKey(ownerId, request.idempotencyKey());
        if (existing.isEmpty()) {
            availability.requireNewWorkAllowed(ownerId);
        }
        AudiobookGenerationRecord record = existing.orElseGet(() -> {
            UUID revisionId = UUID.randomUUID();
            int version = repository.nextGenerationVersion(ownerId, source.bookLineageKey());
            repository.createPronunciationRevision(source, revisionId, version, request);
            return required(revisionId);
        });
        if (!repository.matchesPronunciationRevision(record.id(), sourceId, request)) {
            throw new IllegalArgumentException("audiobook pronunciation revision idempotency conflict");
        }
        if (!"COMPLETED".equals(record.status()) && record.pronunciationTaskId() == null) {
            availability.requireNewWorkAllowed(ownerId);
            UUID taskId = schedulerClient.createTask("reader_prepare_audiobook_pronunciation_revision",
                    "reader_prepare_audiobook_pronunciation_revision:" + record.id() + ":v"
                            + record.generationVersion(), "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                    "generationId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            repository.bindPronunciationTask(record.id(), taskId);
            record = required(record.id());
        }
        if (existing.isEmpty()) {
            metrics.recordAccepted(record.mode());
        }
        return view(record);
    }

    /**
     * 按所有者查询有声书生成运行。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 生成运行视图
     */
    public AudiobookGenerationView get(UUID id, long ownerId) {
        AudiobookGenerationRecord record = required(id);
        if (record.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(id);
        }
        synchronizeTerminalTask(record);
        record = required(id);
        return view(record);
    }

    /**
     * 请求取消当前仍在运行的有声书阶段。取消不会删除已冻结正文、分析结果或已就绪章节音频。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 取消已受理后的运行视图
     */
    @Transactional
    public AudiobookGenerationView cancel(UUID id, long ownerId) {
        AudiobookGenerationRecord record = required(id);
        if (record.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(id);
        }
        UUID taskId = activeTaskId(record);
        if (!isTerminal(record.status()) && taskId != null) {
            schedulerClient.cancel(taskId);
            // 调度器已同步取消时立即反映终态；异步取消则保留队列状态供后续轮询。
            synchronizeTerminalTask(record);
        }
        return view(required(id));
    }

    /**
     * 从最后一个失败、超时或已取消的阶段重新提交任务。已成功冻结或合成的内容保持复用。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 新任务已受理后的运行视图
     */
    @Transactional(noRollbackFor = AudiobookDailyCharacterQuotaExceededException.class)
    public AudiobookGenerationView retry(UUID id, long ownerId) {
        AudiobookGenerationRecord record = required(id);
        if (record.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(id);
        }
        synchronizeTerminalTask(record);
        record = required(id);
        if (!isRetryableTerminal(record.status())) {
            throw new IllegalStateException("audiobook generation is not ready for retry");
        }
        if (ErrorCode.AUDIOBOOK_CHARACTER_LIMIT_EXCEEDED.code().equals(record.errorCode())) {
            throw new IllegalStateException("audiobook generation exceeds the configured character limit");
        }
        availability.requireNewWorkAllowed(ownerId);
        if (ErrorCode.AUDIOBOOK_DAILY_CHARACTER_QUOTA_EXCEEDED.code().equals(record.errorCode())) {
            // 同一天仍不足时立即拒绝；跨自然日会自动获得新的 usage_date 行并可恢复。
            reserveSynthesisQuota(record, "INITIAL_SYNTHESIS");
        }
        RetryStage stage = retryStage(record);
        UUID previousTaskId = activeTaskId(record);
        if (previousTaskId == null) {
            throw new IllegalStateException("audiobook generation retry task is unavailable");
        }
        UUID taskId = schedulerClient.createTask(stage.taskName(), retryIdempotencyKey(record, stage, previousTaskId),
                "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                "generationId", record.id().toString(), "ownerId", record.ownerId(),
                "storageRoot", properties.ebookStorageRoot()));
        bindRetryTask(record.id(), stage, taskId);
        return view(required(id));
    }

    /**
     * 查询指定用户的版本化章节播放清单。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 播放清单
     */
    public AudiobookPlaybackManifest playbackManifest(UUID id, long ownerId) {
        AudiobookGenerationRecord record = required(id);
        if (record.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(id);
        }
        return repository.findPlaybackManifest(id)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 返回当前所有者已就绪章节的内部音频定位信息。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @param chapterIndex 章节序号
     * @return 可播放章节
     */
    public AudiobookAudioChapter playableChapter(UUID id, long ownerId, int chapterIndex) {
        return repository.findPlayableChapter(id, ownerId, chapterIndex)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 将当前所有者已就绪章节音频转发给内部调用方。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @param chapterIndex 章节序号
     * @param range 客户端请求范围
     * @param response HTTP 响应
     */
    public void streamChapterAudio(UUID id, long ownerId, int chapterIndex, String range,
                                   jakarta.servlet.http.HttpServletResponse response) {
        audioStreamClient.stream(playableChapter(id, ownerId, chapterIndex), range, response);
    }

    /**
     * 将当前所有者可试听的已审核音色样音转发给内部调用方。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @param provider 音色供应商标识
     * @param voiceType 音色标识
     * @param range 客户端请求范围
     * @param response HTTP 响应
     */
    public void streamVoicePreview(UUID id, long ownerId, String provider, String voiceType, String range,
                                   jakarta.servlet.http.HttpServletResponse response) {
        AudiobookVoicePreview preview = repository.findVoicePreview(id, ownerId, provider, voiceType)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
        audioStreamClient.stream(preview.storageUri(), preview.format(), range, response);
    }

    /**
     * 对一个已完成 generation 幂等创建 ZIP 导出任务。
     *
     * @param generationId 已完成 generation 标识
     * @param ownerId 当前所有者标识
     * @param request 导出请求
     * @return 已受理或复用的导出摘要
     */
    @Transactional
    public AudiobookExportView createExport(UUID generationId, long ownerId, CreateAudiobookExportRequest request) {
        AudiobookGenerationRecord generation = required(generationId);
        if (generation.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(generationId);
        }
        if (!"COMPLETED".equals(generation.status())) {
            throw new IllegalStateException("audiobook export requires a completed generation");
        }
        int chapterCount = repository.chapterCount(generationId);
        if (chapterCount <= 0 || repository.readySynthesisCount(generationId) != chapterCount) {
            throw new IllegalStateException("audiobook export source chapters are incomplete");
        }
        AudiobookExportRecord record = exportRepository.findByIdempotencyKey(ownerId, request.idempotencyKey())
                .orElseGet(() -> exportRepository.create(UUID.randomUUID(), ownerId, generationId, chapterCount, request));
        if (!exportRepository.matches(record.id(), generationId, request)) {
            throw new IllegalArgumentException("audiobook export idempotency conflict");
        }
        if (!"COMPLETED".equals(record.status()) && record.taskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_export_audiobook",
                    "reader_export_audiobook:" + record.id(), "READER_AUDIOBOOK_EXPORT", record.id(), 30, Map.of(
                    "exportId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            exportRepository.bindTask(record.id(), taskId);
            record = requiredExport(record.id());
        }
        return exportView(record);
    }

    /**
     * 查询当前所有者的有声书导出任务。
     *
     * @param generationId generation 标识
     * @param exportId 导出标识
     * @param ownerId 当前所有者标识
     * @return 导出摘要
     */
    public AudiobookExportView getExport(UUID generationId, UUID exportId, long ownerId) {
        AudiobookExportRecord record = requiredExport(exportId);
        if (record.ownerId() != ownerId || !record.generationId().equals(generationId)) {
            throw new AudiobookExportNotFoundException(exportId);
        }
        synchronizeTerminalExportTask(record);
        return exportView(requiredExport(exportId));
    }

    /**
     * 仅供后台执行器读取一份不可变归档的受控章节输入。
     *
     * @param exportId 导出标识
     * @return 受控归档输入
     */
    public AudiobookExportInput exportInput(UUID exportId) {
        return exportRepository.findInput(exportId).orElseThrow(() -> new AudiobookExportNotFoundException(exportId));
    }

    /**
     * 保存后台执行器发布的归档结果。
     *
     * @param exportId 导出标识
     * @param result 已校验归档结果
     * @return 导出摘要
     */
    @Transactional
    public AudiobookExportView completeExport(UUID exportId, AudiobookExportResult result) {
        return exportView(exportRepository.complete(exportId, result));
    }

    /**
     * 将当前所有者已完成的 ZIP 归档转发给内部网关。
     *
     * @param generationId generation 标识
     * @param exportId 导出标识
     * @param ownerId 当前所有者标识
     * @param response HTTP 响应
     */
    public void streamExport(UUID generationId, UUID exportId, long ownerId,
                             jakarta.servlet.http.HttpServletResponse response) {
        AudiobookExportRecord record = requiredExport(exportId);
        if (record.ownerId() != ownerId || !record.generationId().equals(generationId)) {
            throw new AudiobookExportNotFoundException(exportId);
        }
        AudiobookExportArchive archive = exportRepository.findCompletedArchive(exportId, ownerId)
                .orElseThrow(AudiobookExportUnavailableException::new);
        exportStreamClient.stream(archive, response);
    }

    /**
     * 返回正文投影任务的受控输入，仅供内部执行器读取。
     *
     * @param id 生成运行标识
     * @return 正文投影输入
     */
    public AudiobookTextProjectionInput textProjectionInput(UUID id) {
        return repository.findTextProjectionInput(id)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 保存执行器已发布的正文快照批次。
     *
     * @param id 生成运行标识
     * @param request 正文快照批次
     * @return 写入数量
     */
    @Transactional
    public int saveTextProjectionBatch(UUID id, AudiobookTextProjectionBatchRequest request) {
        required(id);
        return repository.saveTextProjectionBatch(id, request);
    }

    /**
     * 标记所有章节正文快照已冻结。
     *
     * @param id 生成运行标识
     * @return 正文冻结结果
     */
    @Transactional(noRollbackFor = {AudiobookCharacterLimitExceededException.class,
            AudiobookDailyCharacterQuotaExceededException.class})
    public AudiobookTextProjectionResult completeTextProjection(UUID id) {
        AudiobookGenerationRecord record = required(id);
        var projectionSummary = repository.projectedTextSummary(id);
        if (projectionSummary.characterCount() > properties.audiobookMaximumCharactersPerGeneration()) {
            // 在任何模型或 TTS 请求之前持久化拒绝状态，防止超大正文产生不可控供应商成本。
            repository.failForProjectedTextLimit(id, ErrorCode.AUDIOBOOK_CHARACTER_LIMIT_EXCEEDED.code());
            metrics.recordRejected("CHARACTER_LIMIT");
            throw new AudiobookCharacterLimitExceededException();
        }
        int projectedChapterCount = repository.completeTextProjection(id);
        applyIncrementalReuse(record);
        reserveSynthesisQuota(record, "INITIAL_SYNTHESIS");
        if (record.analysisTaskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_analyze_audiobook_book",
                    "reader_analyze_audiobook_book:" + record.id() + ":v" + record.generationVersion(),
                    "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                    "generationId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            repository.bindBookAnalysisTask(record.id(), taskId);
        }
        metrics.recordTextProjected(record.mode(), projectionSummary.characterCount(),
                elapsedMilliseconds(record.startedAt()));
        return new AudiobookTextProjectionResult(id, projectedChapterCount, projectionSummary.characterCount());
    }

    private void applyIncrementalReuse(AudiobookGenerationRecord record) {
        if (record.mode() != com.yuyutian.mytools.reader.model.AudiobookGenerationMode.INCREMENTAL) {
            return;
        }
        var previousId = repository.findPreviousCompletedGeneration(record.bookLineageKey(), record.generationVersion());
        var previous = previousId.map(repository::chapterSnapshots).orElseGet(java.util.List::of);
        var current = repository.chapterSnapshots(record.id());
        var plans = revisionPlanner.plan(previous, current);
        // 只有纯追加或改序且没有删除、正文改动时，旧人物图谱才可作为本次分析的冻结输入复用。
        boolean reusableAnalysis = previousId.isPresent() && revisionPlanner.removedIdentities(previous, current).isEmpty()
                && plans.stream().noneMatch(plan -> plan.change()
                == com.yuyutian.mytools.reader.model.AudiobookChapterChange.MODIFIED);
        repository.applyIncrementalPlan(record.id(), previousId.orElse(null), plans, reusableAnalysis);
    }

    private void reserveSynthesisQuota(AudiobookGenerationRecord record, String reservationType) {
        long characterCount = repository.pendingSynthesisCharacterCount(record.id());
        if (repository.reserveDailySynthesisCharacters(record.id(), record.ownerId(), reservationType, characterCount,
                properties.audiobookDailyCharactersPerOwner())) {
            return;
        }
        // 预算拒绝必须在提交任何后续供应商任务之前持久化，避免失败重试绕过成本控制。
        repository.failForSynthesisQuota(record.id(), ErrorCode.AUDIOBOOK_DAILY_CHARACTER_QUOTA_EXCEEDED.code());
        metrics.recordRejected("DAILY_CHARACTER_QUOTA");
        throw new AudiobookDailyCharacterQuotaExceededException();
    }

    /**
     * 返回章节合成任务的受控输入，仅供内部执行器读取。
     *
     * @param id 生成运行标识
     * @return 章节合成输入
     */
    public AudiobookSynthesisInput synthesisInput(UUID id) {
        return repository.findSynthesisInput(id)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 返回读音修订执行器扫描冻结章节所需的受控输入。
     *
     * @param id 读音修订版本标识
     * @return 需精确匹配的词条及冻结章节清单
     */
    public AudiobookPronunciationPreparationInput pronunciationPreparationInput(UUID id) {
        return repository.findPronunciationPreparationInput(id)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 保存读音修订的精确命中章节，并在必要时提交最小章节合成任务。
     *
     * @param id 读音修订版本标识
     * @param chapterIndexes 受限执行器基于冻结正文计算的命中章节
     * @return 已失效并重新排队的章节数
     */
    @Transactional(noRollbackFor = AudiobookDailyCharacterQuotaExceededException.class)
    public AudiobookPronunciationPreparationResult completePronunciationPreparation(UUID id,
                                                                                      java.util.List<Integer> chapterIndexes) {
        AudiobookGenerationRecord record = required(id);
        int affectedChapterCount = repository.applyPronunciationMatches(id, chapterIndexes);
        record = required(id);
        if (affectedChapterCount == 0) {
            repository.completeSynthesis(id);
            metrics.recordCompleted(record.mode());
            return new AudiobookPronunciationPreparationResult(id, 0);
        }
        if (record.synthesisTaskId() == null) {
            reserveSynthesisQuota(record, "PRONUNCIATION_REPAIR");
            UUID taskId = schedulerClient.createTask("reader_synthesize_audiobook",
                    "reader_synthesize_audiobook:" + record.id() + ":v" + record.generationVersion(),
                    "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                    "generationId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            repository.bindSynthesisTask(record.id(), taskId);
        }
        return new AudiobookPronunciationPreparationResult(id, affectedChapterCount);
    }

    /**
     * 返回全书人物和说话人分析任务的受控输入，仅供内部执行器读取。
     *
     * @param id 生成运行标识
     * @return 全书分析输入
     */
    public AudiobookBookAnalysisInput bookAnalysisInput(UUID id) {
        return repository.findBookAnalysisInput(id)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 查询当前所有者可审核的全书分析结果。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 全书结构化分析视图
     */
    public AudiobookBookAnalysisView bookAnalysis(UUID id, long ownerId) {
        AudiobookGenerationRecord record = required(id);
        if (record.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(id);
        }
        return repository.findBookAnalysis(id).orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 查询当前所有者可审核的冻结音色计划。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 音色计划视图
     */
    public AudiobookVoicePlanView voicePlan(UUID id, long ownerId) {
        AudiobookGenerationRecord record = required(id);
        if (record.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(id);
        }
        return repository.findVoicePlan(id).orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 查询当前所有者在该版本审核时可选择的已启用音色目录。
     *
     * @param id 生成运行标识
     * @param ownerId 所有者标识
     * @return 不含密钥的音色目录
     */
    public AudiobookVoiceCatalogView voiceCatalog(UUID id, long ownerId) {
        AudiobookGenerationRecord record = required(id);
        if (record.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(id);
        }
        return repository.findVoiceCatalog(id).orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 查询当前所有者可审核的冻结读音词典。
     *
     * @param id 生成运行标识
     * @param ownerId 当前所有者标识
     * @return 冻结词典视图
     */
    public AudiobookPronunciationDictionaryView pronunciationDictionary(UUID id, long ownerId) {
        AudiobookGenerationRecord record = required(id);
        if (record.ownerId() != ownerId) {
            throw new AudiobookGenerationNotFoundException(id);
        }
        return repository.findPronunciationDictionary(id)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 保存执行器归并后的全书人物、别名、关系和说话人归因。
     *
     * @param id 生成运行标识
     * @param request 全书分析结果
     * @return 已保存实体数量摘要
     */
    @Transactional
    public AudiobookBookAnalysisResult saveBookAnalysis(UUID id, AudiobookBookAnalysisBatchRequest request) {
        required(id);
        return repository.saveBookAnalysis(id, request);
    }

    /**
     * 完成全书分析并提交全书音色匹配任务。
     *
     * @param id 生成运行标识
     * @return 已保存实体数量摘要
     */
    @Transactional
    public AudiobookBookAnalysisResult completeBookAnalysis(UUID id) {
        AudiobookGenerationRecord record = required(id);
        AudiobookBookAnalysisResult result = repository.completeBookAnalysis(id);
        if (record.voiceMatchTaskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_match_audiobook_voices",
                    "reader_match_audiobook_voices:" + record.id() + ":v" + record.generationVersion(),
                    "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                    "generationId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            repository.bindVoiceMatchTask(record.id(), taskId);
        }
        return result;
    }

    /**
     * 返回全书音色匹配任务的受控输入，仅供内部执行器读取。
     *
     * @param id 生成运行标识
     * @return 音色匹配输入
     */
    public AudiobookVoiceMatchInput voiceMatchInput(UUID id) {
        return repository.findVoiceMatchInput(id)
                .orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    /**
     * 保存执行器为旁白和角色选择的冻结音色计划。
     *
     * @param id 生成运行标识
     * @param request 音色目录与绑定计划
     * @return 已保存绑定数量摘要
     */
    @Transactional
    public AudiobookVoicePlanResult saveVoicePlan(UUID id, AudiobookVoicePlanRequest request) {
        required(id);
        return repository.saveVoicePlan(id, request);
    }

    /**
     * 完成全书音色计划，并在需要时提交分角色章节合成任务。
     *
     * @param id 生成运行标识
     * @return 已冻结绑定数量摘要
     */
    @Transactional
    public AudiobookVoicePlanResult completeVoicePlan(UUID id) {
        AudiobookGenerationRecord record = required(id);
        AudiobookVoicePlanResult result = repository.completeVoicePlan(id);
        if (repository.readySynthesisCount(id) == repository.chapterCount(id)) {
            repository.completeSynthesis(id);
            if (!"COMPLETED".equals(record.status())) {
                metrics.recordCompleted(record.mode());
            }
            return result;
        }
        if (record.synthesisTaskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_synthesize_audiobook",
                    "reader_synthesize_audiobook:" + record.id() + ":v" + record.generationVersion(),
                    "READER_AUDIOBOOK_GENERATION", record.id(), 30, Map.of(
                    "generationId", record.id().toString(), "ownerId", record.ownerId(),
                    "storageRoot", properties.ebookStorageRoot()));
            repository.bindSynthesisTask(record.id(), taskId);
        }
        return result;
    }

    /**
     * 保存执行器已发布的章节音频批次。
     *
     * @param id 生成运行标识
     * @param request 章节音频批次
     * @return 写入数量
     */
    @Transactional
    public int saveSynthesisBatch(UUID id, AudiobookSynthesisBatchRequest request) {
        required(id);
        return repository.saveSynthesisBatch(id, request);
    }

    /**
     * 标记所有章节音频已合成。
     *
     * @param id 生成运行标识
     * @return 章节音频合成结果
     */
    @Transactional
    public AudiobookSynthesisResult completeSynthesis(UUID id) {
        AudiobookGenerationRecord record = required(id);
        int synthesizedChapterCount = repository.completeSynthesis(id);
        if (!"COMPLETED".equals(record.status())) {
            metrics.recordCompleted(record.mode());
        }
        return new AudiobookSynthesisResult(id, synthesizedChapterCount);
    }

    private AudiobookGenerationRecord createRecord(CreateAudiobookGenerationRequest request) {
        var asset = repository.findAsset(request.ownerId(), request.ebookAssetId())
                .orElseThrow(() -> new AudiobookAssetNotFoundException(request.ebookAssetId()));
        Instant now = Instant.now();
        int generationVersion = repository.nextGenerationVersion(request.ownerId(), asset.bookLineageKey());
        AudiobookGenerationRecord record = new AudiobookGenerationRecord(UUID.randomUUID(), request.ownerId(),
                request.ebookAssetId(), asset.bookLineageKey(), request.idempotencyKey(), request.mode(), "ACCEPTED",
                "TEXT_EXTRACTION_PENDING", asset.contentSha256(), generationVersion, null, null,
                null, null, null, asset.chapterCount(), 0, 0, null, now, null, null, now);
        repository.insert(record, asset);
        return record;
    }

    private AudiobookGenerationRecord required(UUID id) {
        return repository.findById(id).orElseThrow(() -> new AudiobookGenerationNotFoundException(id));
    }

    private AudiobookExportRecord requiredExport(UUID id) {
        return exportRepository.findById(id).orElseThrow(() -> new AudiobookExportNotFoundException(id));
    }

    private void synchronizeTerminalTask(AudiobookGenerationRecord record) {
        UUID taskId = activeTaskId(record);
        if (taskId == null || isTerminal(record.status())) {
            return;
        }
        SchedulerResult result = schedulerClient.getResults(taskId);
        // 调度器异步落库期间可能暂时查不到结果，保留当前运行状态等待下一次查询。
        if (result == null || result.status() == null) {
            return;
        }
        String status = result.status();
        if ("FAILED".equals(status) || "TIMED_OUT".equals(status)) {
            repository.updateTerminalTaskStatus(record.id(), status, "READER_022");
            metrics.recordTaskFailure(record.mode(), record.currentStage());
        } else if ("CANCELLED".equals(status)) {
            // 用户主动终止不是供应商或执行器失败，不写失败码也不污染失败率。
            repository.updateTerminalTaskStatus(record.id(), status, null);
        }
    }

    private void synchronizeTerminalExportTask(AudiobookExportRecord record) {
        if (record.taskId() == null || "COMPLETED".equals(record.status())) {
            return;
        }
        SchedulerResult result = schedulerClient.getResults(record.taskId());
        // 调度器异步落库期间可能暂时查不到结果，保留当前运行状态等待下一次查询。
        if (result == null || result.status() == null) {
            return;
        }
        String status = result.status();
        if ("FAILED".equals(status) || "TIMED_OUT".equals(status) || "CANCELLED".equals(status)) {
            exportRepository.updateTerminalTaskStatus(record.id(), status, "READER_025");
        }
    }

    private AudiobookGenerationView view(AudiobookGenerationRecord record) {
        return new AudiobookGenerationView(record.id(), record.status(), record.currentStage(), record.mode(),
                record.generationVersion(), record.requestedChapterCount(), record.completedChapterCount(),
                record.failedChapterCount(), record.errorCode(), record.createdAt(), record.updatedAt());
    }

    private AudiobookExportView exportView(AudiobookExportRecord record) {
        Long sizeBytes = "COMPLETED".equals(record.status()) ? exportRepository.findCompletedArchive(record.id(),
                record.ownerId()).map(AudiobookExportArchive::sizeBytes).orElse(null) : null;
        return new AudiobookExportView(record.id(), record.generationId(), record.format(), record.status(),
                record.currentStage(), record.chapterCount(), sizeBytes, record.errorCode(), record.createdAt(),
                record.updatedAt());
    }

    private long elapsedMilliseconds(Instant startedAt) {
        return startedAt == null ? 0 : Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "TIMED_OUT".equals(status)
                || "CANCELLED".equals(status);
    }

    private boolean isRetryableTerminal(String status) {
        return "FAILED".equals(status) || "TIMED_OUT".equals(status) || "CANCELLED".equals(status);
    }

    private UUID activeTaskId(AudiobookGenerationRecord record) {
        if (record.pronunciationTaskId() != null) {
            return record.pronunciationTaskId();
        }
        if (record.synthesisTaskId() != null) {
            return record.synthesisTaskId();
        }
        if (record.voiceMatchTaskId() != null) {
            return record.voiceMatchTaskId();
        }
        if (record.analysisTaskId() != null) {
            return record.analysisTaskId();
        }
        return record.taskId();
    }

    private RetryStage retryStage(AudiobookGenerationRecord record) {
        if (record.pronunciationTaskId() != null) {
            return RetryStage.PRONUNCIATION_PREPARATION;
        }
        if (record.synthesisTaskId() != null) {
            return RetryStage.SYNTHESIS;
        }
        if (record.voiceMatchTaskId() != null) {
            return RetryStage.VOICE_MATCH;
        }
        if (record.analysisTaskId() != null) {
            return RetryStage.BOOK_ANALYSIS;
        }
        if (record.taskId() != null) {
            return RetryStage.TEXT_PROJECTION;
        }
        throw new IllegalStateException("audiobook generation retry stage is unavailable");
    }

    private String retryIdempotencyKey(AudiobookGenerationRecord record, RetryStage stage, UUID previousTaskId) {
        return "reader_generate_audiobook:" + record.id() + ":v" + record.generationVersion() + ":retry:"
                + stage.name() + ":" + previousTaskId;
    }

    private void bindRetryTask(UUID generationId, RetryStage stage, UUID taskId) {
        switch (stage) {
            case TEXT_PROJECTION -> repository.bindTextProjectionTask(generationId, taskId);
            case BOOK_ANALYSIS -> repository.bindBookAnalysisTask(generationId, taskId);
            case VOICE_MATCH -> repository.bindVoiceMatchTask(generationId, taskId);
            case SYNTHESIS -> repository.bindSynthesisTask(generationId, taskId);
            case PRONUNCIATION_PREPARATION -> repository.bindPronunciationTask(generationId, taskId);
        }
    }

    /** 有声书失败恢复可重新调度的阶段。 */
    private enum RetryStage {
        TEXT_PROJECTION("reader_generate_audiobook"),
        BOOK_ANALYSIS("reader_analyze_audiobook_book"),
        VOICE_MATCH("reader_match_audiobook_voices"),
        SYNTHESIS("reader_synthesize_audiobook"),
        PRONUNCIATION_PREPARATION("reader_prepare_audiobook_pronunciation_revision");

        private final String taskName;

        RetryStage(String taskName) {
            this.taskName = taskName;
        }

        private String taskName() {
            return taskName;
        }
    }
}
