package com.yuyutian.mytools.reader.repository;

import com.yuyutian.mytools.reader.model.AudiobookGenerationMode;
import com.yuyutian.mytools.reader.model.AudiobookGenerationRecord;
import com.yuyutian.mytools.reader.model.AudiobookAudioChapter;
import com.yuyutian.mytools.reader.model.AudiobookVoicePreview;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisInput;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisResult;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisView;
import com.yuyutian.mytools.reader.model.AudiobookVoiceMatchInput;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanRequest;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanResult;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanView;
import com.yuyutian.mytools.reader.model.AudiobookVoiceCatalogView;
import com.yuyutian.mytools.reader.model.AudiobookChapterPlan;
import com.yuyutian.mytools.reader.model.AudiobookChapterSnapshot;
import com.yuyutian.mytools.reader.model.CreateAudiobookSpeakerRevisionRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookVoiceRevisionRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookPronunciationRevisionRequest;
import com.yuyutian.mytools.reader.model.AudiobookPlaybackManifest;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationDictionaryView;
import com.yuyutian.mytools.reader.model.AudiobookPronunciationPreparationInput;
import com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookSynthesisInput;
import com.yuyutian.mytools.reader.model.AudiobookTextProjectionBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookTextProjectionInput;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 有声书生成运行与初始章节清单仓储。
 */
@Repository
public class AudiobookGenerationRepository {

    private static final Set<String> AUDIO_PREVIEW_FORMATS = Set.of("mp3", "aac", "ogg", "wav");
    private static final double SPEAKER_REVISION_MAXIMUM_CONFIDENCE = 0.80d;
    private static final String LEGACY_ANALYSIS_PROVENANCE = "UNKNOWN_LEGACY";
    private static final String EMPTY_PRONUNCIATION_FINGERPRINT =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建有声书运行仓储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public AudiobookGenerationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询属于指定用户的电子书资产快照。
     *
     * @param ownerId 所有者标识
     * @param ebookAssetId 电子书资产标识
     * @return 资产快照
     */
    public Optional<EbookAssetSnapshot> findAsset(long ownerId, UUID ebookAssetId) {
        return jdbcTemplate.query("""
                SELECT id, import_request_id, content_sha256, chapter_count, storage_uri, metadata_json
                FROM ebook_asset WHERE owner_id = ? AND id = ?
                """, (resultSet, rowNumber) -> new EbookAssetSnapshot(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("import_request_id")),
                resultSet.getString("content_sha256"), resultSet.getInt("chapter_count"),
                resultSet.getString("storage_uri"), bookLineageKey(ownerId,
                UUID.fromString(resultSet.getString("id")), resultSet.getString("metadata_json"))),
                ownerId, ebookAssetId.toString()).stream().findFirst();
    }

    /**
     * 按所有者和幂等键查询运行。
     *
     * @param ownerId 所有者标识
     * @param idempotencyKey 幂等键
     * @return 生成运行
     */
    public Optional<AudiobookGenerationRecord> findByIdempotencyKey(long ownerId, String idempotencyKey) {
        return query("WHERE owner_id = ? AND idempotency_key = ?", ownerId, idempotencyKey);
    }

    /**
     * 查询生成运行。
     *
     * @param id 生成运行标识
     * @return 生成运行
     */
    public Optional<AudiobookGenerationRecord> findById(UUID id) {
        return query("WHERE id = ?", id.toString());
    }

    /**
     * 返回同一书籍谱系的下一个生成版本。
     *
     * @param ownerId 所有者标识
     * @param bookLineageKey 书籍谱系标识
     * @return 下一个版本号
     */
    public int nextGenerationVersion(long ownerId, String bookLineageKey) {
        Integer latest = jdbcTemplate.queryForObject("""
                SELECT MAX(generation_version) FROM audiobook_generation
                WHERE owner_id = ? AND book_lineage_key = ?
                """, Integer.class, ownerId, bookLineageKey);
        return latest == null ? 1 : latest + 1;
    }

    /**
     * 插入运行，并从冻结目录建立全部章节待处理清单。
     *
     * @param record 生成运行
     * @param asset 电子书资产快照
     */
    public void insert(AudiobookGenerationRecord record, EbookAssetSnapshot asset) {
        Instant now = record.createdAt();
        jdbcTemplate.update("""
                INSERT INTO audiobook_generation
                    (id, owner_id, ebook_asset_id, book_lineage_key, idempotency_key, mode, status, current_stage,
                     book_content_sha256, generation_version, task_instance_id, requested_chapter_count,
                     completed_chapter_count, failed_chapter_count, error_code, created_at, started_at,
                     finished_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id().toString(), record.ownerId(), record.ebookAssetId().toString(),
                record.bookLineageKey(), record.idempotencyKey(), record.mode().name(), record.status(),
                record.currentStage(),
                record.bookContentSha256(), record.generationVersion(), null, record.requestedChapterCount(),
                record.completedChapterCount(), record.failedChapterCount(), record.errorCode(),
                Timestamp.from(record.createdAt()), null, null, Timestamp.from(record.updatedAt()));
        List<CatalogEntry> catalog = catalog(asset.importRequestId());
        for (CatalogEntry entry : catalog) {
            jdbcTemplate.update("""
                    INSERT INTO audiobook_generation_chapter
                        (generation_id, chapter_index, chapter_identity_sha256, chapter_content_sha256,
                        chapter_title, source_resource_ref, source_start_offset, source_end_offset,
                        source_status, analysis_status, synthesis_status, audio_asset_id, timing_asset_id,
                        duration_ms, retry_count, error_code, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 'PENDING', 'PENDING', NULL, NULL, NULL, 0, NULL, ?, ?)
                    """, record.id().toString(), entry.index(), chapterIdentity(asset.contentSha256(),
                    entry.resourceRef()), null, entry.title(), entry.resourceRef(), entry.startOffset(),
                    entry.endOffset(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    /**
     * 复制已完成版本的冻结输入、分析和音频引用，并对一个音色绑定建立不可变修订版。
     *
     * @param parent 已完成的源运行
     * @param revisionId 新运行标识
     * @param generationVersion 新版本号
     * @param request 人工音色替换请求
     */
    public void createVoiceRevision(AudiobookGenerationRecord parent, UUID revisionId, int generationVersion,
                                    CreateAudiobookVoiceRevisionRequest request) {
        FrozenGeneration frozen = frozenGeneration(parent.id());
        String roleKey = request.roleKey().trim();
        String provider = request.provider().trim();
        String voiceType = request.voiceType().trim();
        List<VoiceBindingSnapshot> sourceBindings = voiceBindingSnapshots(parent.id());
        VoiceBindingSnapshot sourceBinding = sourceBindings.stream()
                .filter(binding -> roleKey.equals(binding.roleKey())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("audiobook voice revision role does not exist"));
        EnabledVoice selectedVoice = enabledVoice(provider, voiceType);
        if ("NARRATOR".equals(roleKey) && !selectedVoice.narratorEligible()) {
            throw new IllegalArgumentException("audiobook narrator voice is not eligible");
        }
        if (provider.equals(sourceBinding.provider()) && voiceType.equals(sourceBinding.voiceType())) {
            throw new IllegalArgumentException("audiobook voice revision does not change the selected voice");
        }
        Set<Integer> affectedChapters = affectedChapters(parent.id(), sourceBinding, roleKey);
        if (affectedChapters.isEmpty()) {
            throw new IllegalArgumentException("audiobook voice revision has no affected chapters");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO audiobook_generation
                    (id, owner_id, ebook_asset_id, book_lineage_key, idempotency_key, mode, status, current_stage,
                     book_content_sha256, generation_version, task_instance_id, analysis_task_instance_id,
                     voice_match_task_instance_id, synthesis_task_instance_id, analysis_fingerprint_sha256,
                     analysis_model_version, analysis_rule_version, voice_plan_fingerprint_sha256,
                     voice_quality_gate_attestation_sha256,
                     requested_chapter_count, completed_chapter_count,
                     failed_chapter_count, error_code, parent_generation_id, revision_role_key, revision_provider,
                     revision_voice_type, created_at, started_at, finished_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'REPAIR', 'VOICE_PLAN_READY', 'VOICE_PLAN_READY', ?, ?, NULL, NULL, NULL,
                        NULL, ?, ?, ?, ?, ?, ?, 0, 0, NULL, ?, ?, ?, ?, ?, ?, NULL, ?)
                """, revisionId.toString(), parent.ownerId(), parent.ebookAssetId().toString(),
                parent.bookLineageKey(), request.idempotencyKey(), parent.bookContentSha256(), generationVersion,
                frozen.analysisFingerprint(), frozen.analysisModelVersion(), frozen.analysisRuleVersion(),
                voiceRevisionFingerprint(frozen.voicePlanFingerprint(), roleKey, provider, voiceType),
                frozen.qualityGateAttestationSha256(),
                parent.requestedChapterCount(), parent.id().toString(), roleKey, provider, voiceType,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        copyChapters(parent.id(), revisionId, now, "VOICE_REPAIR_REUSED");
        copyAudioAssets(parent.id(), revisionId, now);
        Map<UUID, UUID> characterIds = copyCharacters(parent.id(), revisionId, now);
        copyRelationships(parent.id(), revisionId, characterIds, now);
        copySpeechAttributions(parent.id(), revisionId, characterIds, now);
        copyVoiceBindings(revisionId, sourceBindings, characterIds, roleKey, selectedVoice, now);
        for (int chapterIndex : affectedChapters) {
            jdbcTemplate.update("""
                    DELETE FROM audiobook_audio_asset WHERE generation_id = ? AND chapter_index = ?
                    """, revisionId.toString(), chapterIndex);
            jdbcTemplate.update("""
                    UPDATE audiobook_generation_chapter
                    SET change_type = 'VOICE_REPAIR', reused_from_generation_id = NULL,
                        reused_from_chapter_index = NULL, synthesis_status = 'PENDING', audio_asset_id = NULL,
                        audio_storage_uri = NULL, audio_content_sha256 = NULL, audio_size_bytes = NULL,
                        audio_format = NULL, duration_ms = NULL, error_code = NULL, updated_at = ?
                    WHERE generation_id = ? AND chapter_index = ?
                    """, Timestamp.from(now), revisionId.toString(), chapterIndex);
        }
    }

    /**
     * 判断一个已有运行是否正是同一源版本、角色和目标音色的修订请求。
     *
     * @param generationId 已有运行标识
     * @param parentGenerationId 源运行标识
     * @param request 修订请求
     * @return 是否匹配同一修订请求
     */
    public boolean matchesVoiceRevision(UUID generationId, UUID parentGenerationId,
                                        CreateAudiobookVoiceRevisionRequest request) {
        return jdbcTemplate.query("""
                SELECT parent_generation_id, revision_role_key, revision_provider, revision_voice_type, mode
                FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new VoiceRevisionIdentity(
                resultSet.getString("parent_generation_id"), resultSet.getString("revision_role_key"),
                resultSet.getString("revision_provider"), resultSet.getString("revision_voice_type"),
                resultSet.getString("mode")), generationId.toString()).stream().findFirst()
                .map(identity -> "REPAIR".equals(identity.mode())
                        && parentGenerationId.toString().equals(identity.parentGenerationId())
                        && request.roleKey().trim().equals(identity.roleKey())
                        && request.provider().trim().equals(identity.provider())
                        && request.voiceType().trim().equals(identity.voiceType()))
                .orElse(false);
    }

    /**
     * 复制完成版本的冻结输入、分析和音频引用，并对一条低置信度说话人归因建立不可变修订版。
     *
     * @param parent 已完成的源运行
     * @param revisionId 新运行标识
     * @param generationVersion 新版本号
     * @param request 人工说话人归因修订请求
     */
    public void createSpeakerRevision(AudiobookGenerationRecord parent, UUID revisionId, int generationVersion,
                                      CreateAudiobookSpeakerRevisionRequest request) {
        FrozenGeneration frozen = frozenGeneration(parent.id());
        SourceSpeechAttribution source = sourceSpeechAttribution(parent.id(), request.chapterIndex(),
                request.sequenceNumber());
        if (source.confidence() >= SPEAKER_REVISION_MAXIMUM_CONFIDENCE) {
            throw new IllegalArgumentException("audiobook speaker revision requires a low-confidence segment");
        }
        SpeakerRevisionTarget target = speakerRevisionTarget(parent.id(), request);
        if (source.speakerKind().equals(target.speakerKind())
                && java.util.Objects.equals(source.speakerCharacterId(), target.sourceCharacterId())) {
            throw new IllegalArgumentException("audiobook speaker revision does not change the attribution");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO audiobook_generation
                    (id, owner_id, ebook_asset_id, book_lineage_key, idempotency_key, mode, status, current_stage,
                     book_content_sha256, generation_version, task_instance_id, analysis_task_instance_id,
                     voice_match_task_instance_id, synthesis_task_instance_id, analysis_fingerprint_sha256,
                     analysis_model_version, analysis_rule_version, voice_plan_fingerprint_sha256,
                     voice_quality_gate_attestation_sha256,
                     requested_chapter_count, completed_chapter_count,
                     failed_chapter_count, error_code, parent_generation_id, revision_chapter_index,
                     revision_sequence_number, revision_speaker_kind, revision_speaker_canonical_name,
                     created_at, started_at, finished_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'REPAIR', 'VOICE_PLAN_READY', 'VOICE_PLAN_READY', ?, ?, NULL, NULL, NULL,
                        NULL, ?, ?, ?, ?, ?, ?, 0, 0, NULL, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """, revisionId.toString(), parent.ownerId(), parent.ebookAssetId().toString(),
                parent.bookLineageKey(), request.idempotencyKey(), parent.bookContentSha256(), generationVersion,
                speakerRevisionFingerprint(frozen.analysisFingerprint(), request, target.canonicalName()),
                frozen.analysisModelVersion(), frozen.analysisRuleVersion(), frozen.voicePlanFingerprint(),
                frozen.qualityGateAttestationSha256(),
                parent.requestedChapterCount(), parent.id().toString(),
                request.chapterIndex(), request.sequenceNumber(), target.speakerKind(), target.canonicalName(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        copyChapters(parent.id(), revisionId, now, "SPEAKER_REPAIR_REUSED");
        copyAudioAssets(parent.id(), revisionId, now);
        Map<UUID, UUID> characterIds = copyCharacters(parent.id(), revisionId, now);
        copyRelationships(parent.id(), revisionId, characterIds, now);
        copySpeechAttributions(parent.id(), revisionId, characterIds, now);
        copyVoiceBindings(revisionId, voiceBindingSnapshots(parent.id()), characterIds, null, null, now);
        UUID revisionCharacterId = target.sourceCharacterId() == null ? null
                : requiredCharacterId(characterIds, UUID.fromString(target.sourceCharacterId()));
        jdbcTemplate.update("""
                UPDATE audiobook_speech_attribution
                SET speaker_kind = ?, speaker_character_id = ?, confidence = 1.0000, review_status = 'MANUAL',
                    updated_at = ?
                WHERE generation_id = ? AND chapter_index = ? AND sequence_number = ?
                """, target.speakerKind(), revisionCharacterId == null ? null : revisionCharacterId.toString(),
                Timestamp.from(now), revisionId.toString(), request.chapterIndex(), request.sequenceNumber());
        jdbcTemplate.update("""
                DELETE FROM audiobook_audio_asset WHERE generation_id = ? AND chapter_index = ?
                """, revisionId.toString(), request.chapterIndex());
        jdbcTemplate.update("""
                UPDATE audiobook_generation_chapter
                SET change_type = 'SPEAKER_REPAIR', reused_from_generation_id = NULL,
                    reused_from_chapter_index = NULL, synthesis_status = 'PENDING', audio_asset_id = NULL,
                    audio_storage_uri = NULL, audio_content_sha256 = NULL, audio_size_bytes = NULL,
                    audio_format = NULL, duration_ms = NULL, error_code = NULL, updated_at = ?
                WHERE generation_id = ? AND chapter_index = ?
                """, Timestamp.from(now), revisionId.toString(), request.chapterIndex());
    }

    /**
     * 判断一个已有运行是否正是同一源版本和同一片段人工归因修订请求。
     *
     * @param generationId 已有运行标识
     * @param parentGenerationId 源运行标识
     * @param request 修订请求
     * @return 是否匹配同一修订请求
     */
    public boolean matchesSpeakerRevision(UUID generationId, UUID parentGenerationId,
                                          CreateAudiobookSpeakerRevisionRequest request) {
        return jdbcTemplate.query("""
                SELECT parent_generation_id, revision_chapter_index, revision_sequence_number,
                       revision_speaker_kind, revision_speaker_canonical_name, mode
                FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new SpeakerRevisionIdentity(
                resultSet.getString("parent_generation_id"), resultSet.getObject("revision_chapter_index", Integer.class),
                resultSet.getObject("revision_sequence_number", Integer.class),
                resultSet.getString("revision_speaker_kind"), resultSet.getString("revision_speaker_canonical_name"),
                resultSet.getString("mode")), generationId.toString()).stream().findFirst()
                .map(identity -> "REPAIR".equals(identity.mode())
                        && parentGenerationId.toString().equals(identity.parentGenerationId())
                        && Integer.valueOf(request.chapterIndex()).equals(identity.chapterIndex())
                        && Integer.valueOf(request.sequenceNumber()).equals(identity.sequenceNumber())
                        && request.speakerKind().trim().equals(identity.speakerKind())
                        && java.util.Objects.equals(normalizedSpeakerCanonicalName(request),
                        identity.speakerCanonicalName()))
                .orElse(false);
    }

    /**
     * 复制完成版本的冻结输入和可复用音频，为一个读音词条修订建立不可变子版本。
     *
     * @param parent 已完成的源运行
     * @param revisionId 新运行标识
     * @param generationVersion 新版本号
     * @param request 人工读音修订请求
     */
    public void createPronunciationRevision(AudiobookGenerationRecord parent, UUID revisionId, int generationVersion,
                                            CreateAudiobookPronunciationRevisionRequest request) {
        FrozenGeneration frozen = frozenGeneration(parent.id());
        if (voiceBindingSnapshots(parent.id()).stream().anyMatch(binding -> !binding.ssmlSupported())) {
            // 读音词典不能在未验证的音色上静默退化为纯文本合成。
            throw new IllegalStateException("audiobook pronunciation revision requires SSML-capable frozen voices");
        }
        String term = request.term().trim();
        String pinyin = request.pinyin().trim().toLowerCase(java.util.Locale.ROOT);
        String currentPinyin = pronunciation(parent.id(), term);
        if (pinyin.equals(currentPinyin)) {
            throw new IllegalArgumentException("audiobook pronunciation revision does not change the selected term");
        }
        Instant now = Instant.now();
        String sourcePronunciationFingerprint = frozen.pronunciationFingerprint() == null
                ? EMPTY_PRONUNCIATION_FINGERPRINT : frozen.pronunciationFingerprint();
        jdbcTemplate.update("""
                INSERT INTO audiobook_generation
                    (id, owner_id, ebook_asset_id, book_lineage_key, idempotency_key, mode, status, current_stage,
                     book_content_sha256, generation_version, task_instance_id, analysis_task_instance_id,
                     voice_match_task_instance_id, synthesis_task_instance_id, pronunciation_task_instance_id,
                     analysis_fingerprint_sha256, analysis_model_version, analysis_rule_version,
                     voice_plan_fingerprint_sha256, voice_quality_gate_attestation_sha256,
                     pronunciation_fingerprint_sha256, requested_chapter_count,
                     completed_chapter_count, failed_chapter_count, error_code, parent_generation_id,
                     revision_role_key, revision_provider, revision_voice_type, revision_chapter_index,
                     revision_sequence_number, revision_speaker_kind, revision_speaker_canonical_name,
                     revision_pronunciation_term, revision_pronunciation_pinyin, created_at, started_at, finished_at,
                     updated_at)
                VALUES (?, ?, ?, ?, ?, 'REPAIR', 'PRONUNCIATION_PREPARING', 'PREPARING_PRONUNCIATION', ?, ?,
                        NULL, NULL, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, 0, 0, NULL, ?, NULL, NULL, NULL, NULL,
                        NULL, NULL, NULL, ?, ?, ?, ?, NULL, ?)
                """, revisionId.toString(), parent.ownerId(), parent.ebookAssetId().toString(),
                parent.bookLineageKey(), request.idempotencyKey(), parent.bookContentSha256(), generationVersion,
                frozen.analysisFingerprint(), frozen.analysisModelVersion(), frozen.analysisRuleVersion(),
                frozen.voicePlanFingerprint(), frozen.qualityGateAttestationSha256(),
                pronunciationRevisionFingerprint(sourcePronunciationFingerprint, term, pinyin),
                parent.requestedChapterCount(), parent.id().toString(), term, pinyin, Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
        copyChapters(parent.id(), revisionId, now, "PRONUNCIATION_REPAIR_REUSED");
        copyAudioAssets(parent.id(), revisionId, now);
        Map<UUID, UUID> characterIds = copyCharacters(parent.id(), revisionId, now);
        copyRelationships(parent.id(), revisionId, characterIds, now);
        copySpeechAttributions(parent.id(), revisionId, characterIds, now);
        copyVoiceBindings(revisionId, voiceBindingSnapshots(parent.id()), characterIds, null, null, now);
        copyPronunciations(parent.id(), revisionId, now);
        upsertPronunciation(revisionId, term, pinyin, now);
    }

    /**
     * 判断一个已有运行是否正是同一源版本、词条和读法的读音修订请求。
     *
     * @param generationId 已有运行标识
     * @param parentGenerationId 源运行标识
     * @param request 修订请求
     * @return 是否匹配同一修订请求
     */
    public boolean matchesPronunciationRevision(UUID generationId, UUID parentGenerationId,
                                                CreateAudiobookPronunciationRevisionRequest request) {
        return jdbcTemplate.query("""
                SELECT parent_generation_id, revision_pronunciation_term, revision_pronunciation_pinyin, mode
                FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new PronunciationRevisionIdentity(
                resultSet.getString("parent_generation_id"), resultSet.getString("revision_pronunciation_term"),
                resultSet.getString("revision_pronunciation_pinyin"), resultSet.getString("mode")),
                generationId.toString()).stream().findFirst().map(identity -> "REPAIR".equals(identity.mode())
                        && parentGenerationId.toString().equals(identity.parentGenerationId())
                        && request.term().trim().equals(identity.term())
                        && request.pinyin().trim().equalsIgnoreCase(identity.pinyin()))
                .orElse(false);
    }

    /**
     * 绑定已受理的正文投影任务，并将运行推进到正文冻结阶段。
     *
     * @param generationId 生成运行标识
     * @param taskId 调度任务标识
     */
    public void bindTextProjectionTask(UUID generationId, UUID taskId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET task_instance_id = ?, status = 'QUEUED', current_stage = 'TEXT_EXTRACTING',
                    error_code = NULL, started_at = COALESCE(started_at, ?), finished_at = NULL, updated_at = ?
                WHERE id = ?
                """, taskId.toString(), Timestamp.from(now), Timestamp.from(now), generationId.toString());
    }

    /**
     * 绑定已受理的章节合成任务，并将运行推进到合成阶段。
     *
     * @param generationId 生成运行标识
     * @param taskId 调度任务标识
     */
    public void bindSynthesisTask(UUID generationId, UUID taskId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET synthesis_task_instance_id = ?, status = 'QUEUED', current_stage = 'SYNTHESIZING',
                    error_code = NULL, finished_at = NULL, updated_at = ?
                WHERE id = ?
                """, taskId.toString(), Timestamp.from(now), generationId.toString());
    }

    /**
     * 绑定读音修订受影响章节计算任务；在执行器确认命中章节前不失效任何现有音频。
     *
     * @param generationId 生成运行标识
     * @param taskId 调度任务标识
     */
    public void bindPronunciationTask(UUID generationId, UUID taskId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET pronunciation_task_instance_id = ?, status = 'QUEUED', current_stage = 'PREPARING_PRONUNCIATION',
                    error_code = NULL, finished_at = NULL, updated_at = ?
                WHERE id = ? AND revision_pronunciation_term IS NOT NULL
                """, taskId.toString(), Timestamp.from(now), generationId.toString());
    }

    /**
     * 绑定已受理的全书分析任务，并将运行推进到人物分析阶段。
     *
     * @param generationId 生成运行标识
     * @param taskId 调度任务标识
     */
    public void bindBookAnalysisTask(UUID generationId, UUID taskId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET analysis_task_instance_id = ?, status = 'QUEUED', current_stage = 'ANALYZING',
                    error_code = NULL, finished_at = NULL, updated_at = ?
                WHERE id = ?
                """, taskId.toString(), Timestamp.from(now), generationId.toString());
    }

    /**
     * 绑定已受理的全书音色匹配任务，并将运行推进到音色计划阶段。
     *
     * @param generationId 生成运行标识
     * @param taskId 调度任务标识
     */
    public void bindVoiceMatchTask(UUID generationId, UUID taskId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET voice_match_task_instance_id = ?, status = 'QUEUED', current_stage = 'MATCHING_VOICES',
                    error_code = NULL, finished_at = NULL, updated_at = ?
                WHERE id = ?
                """, taskId.toString(), Timestamp.from(now), generationId.toString());
    }

    /**
     * 返回任务执行器可读取的冻结正文投影输入。
     *
     * @param generationId 生成运行标识
     * @return 受控正文投影输入
     */
    public Optional<AudiobookTextProjectionInput> findTextProjectionInput(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT generation.id, asset.storage_uri, generation.book_content_sha256
                FROM audiobook_generation generation
                JOIN ebook_asset asset ON asset.id = generation.ebook_asset_id
                WHERE generation.id = ?
                """, (resultSet, rowNumber) -> new ProjectionSource(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("storage_uri"),
                resultSet.getString("book_content_sha256")), generationId.toString()).stream().findFirst()
                .map(source -> new AudiobookTextProjectionInput(source.generationId(), source.storageUri(),
                        source.bookContentSha256(), projectionChapters(source.generationId())));
    }

    /**
     * 幂等写入一批已校验的章节正文快照。
     *
     * @param generationId 生成运行标识
     * @param request 正文快照批次
     * @return 写入条目数
     */
    public int saveTextProjectionBatch(UUID generationId, AudiobookTextProjectionBatchRequest request) {
        Instant now = Instant.now();
        int saved = 0;
        for (AudiobookTextProjectionBatchRequest.Chapter chapter : request.chapters()) {
            ProjectionState existing = jdbcTemplate.query("""
                    SELECT source_status, chapter_content_sha256, source_text_uri
                    FROM audiobook_generation_chapter
                    WHERE generation_id = ? AND chapter_index = ?
                    """, (resultSet, rowNumber) -> new ProjectionState(resultSet.getString("source_status"),
                    resultSet.getString("chapter_content_sha256"), resultSet.getString("source_text_uri")),
                    generationId.toString(), chapter.index()).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("audiobook chapter is not in generation"));
            if ("READY".equals(existing.status()) && (!chapter.contentSha256().equals(existing.contentSha256())
                    || !chapter.storageUri().equals(existing.storageUri()))) {
                throw new IllegalStateException("audiobook chapter projection conflicts with frozen result");
            }
            jdbcTemplate.update("""
                    UPDATE audiobook_generation_chapter
                    SET source_status = 'READY', chapter_content_sha256 = ?, source_text_uri = ?,
                        source_text_size_bytes = ?, source_character_count = ?, error_code = NULL, updated_at = ?
                    WHERE generation_id = ? AND chapter_index = ?
                    """, chapter.contentSha256(), chapter.storageUri(), chapter.sizeBytes(),
                    chapter.characterCount(), Timestamp.from(now), generationId.toString(), chapter.index());
            saved++;
        }
        return saved;
    }

    /**
     * 在所有冻结章节都就绪后推进运行状态。
     *
     * @param generationId 生成运行标识
     * @return 已冻结章节数
     */
    public int completeTextProjection(UUID generationId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter WHERE generation_id = ?
                """, Integer.class, generationId.toString());
        Integer ready = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter
                WHERE generation_id = ? AND source_status = 'READY'
                """, Integer.class, generationId.toString());
        if (total == null || total == 0 || !total.equals(ready)) {
            throw new IllegalStateException("audiobook text projection is incomplete");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET status = 'TEXT_READY', current_stage = 'TEXT_READY', error_code = NULL,
                    started_at = COALESCE(started_at, ?), updated_at = ?
                WHERE id = ? AND synthesis_task_instance_id IS NULL AND status <> 'COMPLETED'
                """, Timestamp.from(now), Timestamp.from(now), generationId.toString());
        return ready;
    }

    /**
     * 汇总已冻结且完整的正文 Unicode 码点数。
     *
     * @param generationId 生成运行标识
     * @return 章节总数和正文码点数
     */
    public ProjectionSummary projectedTextSummary(UUID generationId) {
        ProjectionSummary summary = jdbcTemplate.query("""
                SELECT COUNT(*) AS total_count,
                       SUM(CASE WHEN source_status = 'READY' THEN 1 ELSE 0 END) AS ready_count,
                       COALESCE(SUM(CASE WHEN source_status = 'READY' THEN source_character_count ELSE 0 END), 0)
                           AS character_count
                FROM audiobook_generation_chapter
                WHERE generation_id = ?
                """, (resultSet, rowNumber) -> new ProjectionSummary(resultSet.getInt("total_count"),
                resultSet.getInt("ready_count"), resultSet.getLong("character_count")), generationId.toString())
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "audiobook generation does not exist"));
        if (summary.chapterCount() == 0 || summary.readyChapterCount() != summary.chapterCount()
                || summary.characterCount() <= 0) {
            throw new IllegalStateException("audiobook text projection is incomplete");
        }
        return summary;
    }

    /**
     * 因冻结正文超过已配置成本上限而终止运行。
     *
     * @param generationId 生成运行标识
     * @param errorCode 稳定错误码
     */
    public void failForProjectedTextLimit(UUID generationId, String errorCode) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET status = 'FAILED', current_stage = 'FAILED', error_code = ?, finished_at = ?, updated_at = ?
                WHERE id = ? AND status <> 'COMPLETED'
                """, errorCode, Timestamp.from(now), Timestamp.from(now), generationId.toString());
    }

    /**
     * 汇总当前尚需调用 TTS 的冻结正文码点数；已就绪和复用章节不会重复占用预算。
     *
     * @param generationId 生成运行标识
     * @return 尚待合成的 Unicode 码点数
     */
    public long pendingSynthesisCharacterCount(UUID generationId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(source_character_count), 0)
                FROM audiobook_generation_chapter
                WHERE generation_id = ? AND source_status = 'READY' AND synthesis_status <> 'READY'
                """, Long.class, generationId.toString());
        return count == null ? 0L : count;
    }

    /**
     * 为一次已冻结的章节合成幂等预留所属用户当天的正文预算。
     *
     * @param generationId 生成运行标识
     * @param ownerId 所有者标识
     * @param reservationType 预留类型
     * @param characterCount 将发送到 TTS 的 Unicode 码点数
     * @param dailyLimit 每日最大 Unicode 码点数
     * @return 预算是否足够，已存在相同预留时也返回 true
     */
    public boolean reserveDailySynthesisCharacters(UUID generationId, long ownerId, String reservationType,
                                                    long characterCount, long dailyLimit) {
        if (characterCount < 0 || dailyLimit < 1 || reservationType == null || reservationType.isBlank()
                || reservationType.length() > 32) {
            throw new IllegalArgumentException("audiobook synthesis quota reservation is invalid");
        }
        if (characterCount == 0) {
            return true;
        }
        ensureDailyQuotaUsage(ownerId);
        Long current = jdbcTemplate.queryForObject("""
                SELECT reserved_character_count FROM audiobook_quota_usage
                WHERE owner_id = ? AND usage_date = CURRENT_DATE FOR UPDATE
                """, Long.class, ownerId);
        if (current == null || current < 0) {
            throw new IllegalStateException("audiobook daily quota usage is corrupted");
        }
        List<Long> existing = jdbcTemplate.query("""
                SELECT character_count FROM audiobook_quota_reservation
                WHERE generation_id = ? AND reservation_type = ?
                """, (resultSet, rowNumber) -> resultSet.getLong("character_count"), generationId.toString(),
                reservationType);
        if (!existing.isEmpty()) {
            if (existing.size() != 1 || existing.getFirst() != characterCount) {
                throw new IllegalStateException("audiobook synthesis quota reservation conflicts with frozen input");
            }
            return true;
        }
        if (characterCount > dailyLimit - current) {
            return false;
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_quota_usage
                SET reserved_character_count = ?, updated_at = ?
                WHERE owner_id = ? AND usage_date = CURRENT_DATE
                """, current + characterCount, Timestamp.from(now), ownerId);
        jdbcTemplate.update("""
                INSERT INTO audiobook_quota_reservation
                    (generation_id, reservation_type, owner_id, usage_date, character_count, created_at)
                VALUES (?, ?, ?, CURRENT_DATE, ?, ?)
                """, generationId.toString(), reservationType, ownerId, characterCount, Timestamp.from(now));
        return true;
    }

    /**
     * 失败当前运行以阻止超过每日成本预算后的任何后续分析或合成。
     *
     * @param generationId 生成运行标识
     * @param errorCode 稳定错误码
     */
    public void failForSynthesisQuota(UUID generationId, String errorCode) {
        failForProjectedTextLimit(generationId, errorCode);
    }

    private void ensureDailyQuotaUsage(long ownerId) {
        Instant now = Instant.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO audiobook_quota_usage
                        (owner_id, usage_date, reserved_character_count, created_at, updated_at)
                    VALUES (?, CURRENT_DATE, 0, ?, ?)
                    """, ownerId, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // 同一所有者的并发预留将随后在 FOR UPDATE 行锁上串行化。
        }
    }

    /**
     * 返回任务执行器合成章节音频所需的冻结正文清单。
     *
     * @param generationId 生成运行标识
     * @return 受控章节合成输入
     */
    public Optional<AudiobookSynthesisInput> findSynthesisInput(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT generation.id, narrator.provider AS narrator_provider, narrator.voice_type AS narrator_voice_type,
                       narrator.ssml_supported AS narrator_ssml_supported
                FROM audiobook_generation generation
                JOIN audiobook_voice_binding narrator ON narrator.generation_id = generation.id
                    AND narrator.role_key = 'NARRATOR'
                WHERE generation.id = ? AND generation.synthesis_task_instance_id IS NOT NULL
                  AND generation.voice_plan_fingerprint_sha256 IS NOT NULL
                """, (resultSet, rowNumber) -> new SynthesisPlan(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("narrator_provider"),
                resultSet.getString("narrator_voice_type"), resultSet.getBoolean("narrator_ssml_supported")),
                generationId.toString()).stream().findFirst()
                .map(plan -> new AudiobookSynthesisInput(plan.generationId(), plan.narratorProvider(),
                        plan.narratorVoiceType(), plan.narratorSsmlSupported(), pronunciations(plan.generationId()),
                        synthesisChapters(plan.generationId(), plan.narratorProvider(), plan.narratorVoiceType(),
                                plan.narratorSsmlSupported())));
    }

    /**
     * 返回任务执行器可读取的全书分析输入。
     *
     * @param generationId 生成运行标识
     * @return 受控全书分析输入
     */
    public Optional<AudiobookBookAnalysisInput> findBookAnalysisInput(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id FROM audiobook_generation
                WHERE id = ? AND analysis_task_instance_id IS NOT NULL
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")),
                generationId.toString()).stream().findFirst()
                .map(id -> new AudiobookBookAnalysisInput(id, analysisChapters(id), inheritedAnalysis(id)));
    }

    /**
     * 返回全书音色匹配任务可读取的人物和启用目录快照。
     *
     * @param generationId 生成运行标识
     * @return 音色匹配输入
     */
    public Optional<AudiobookVoiceMatchInput> findVoiceMatchInput(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id FROM audiobook_generation
                WHERE id = ? AND voice_match_task_instance_id IS NOT NULL
                  AND analysis_fingerprint_sha256 IS NOT NULL
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")), generationId.toString())
                .stream().findFirst().map(id -> new AudiobookVoiceMatchInput(id, voiceMatchCharacters(id),
                        enabledVoices(), inheritedVoiceBindings(id)));
    }

    /**
     * 查询一代有声书的可审计全书分析结果。
     *
     * @param generationId 生成运行标识
     * @return 全书结构化分析视图
     */
    public Optional<AudiobookBookAnalysisView> findBookAnalysis(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id, analysis_model_version, analysis_rule_version
                FROM audiobook_generation WHERE id = ? AND analysis_fingerprint_sha256 IS NOT NULL
                """, (resultSet, rowNumber) -> new AnalysisViewIdentity(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("analysis_model_version"),
                resultSet.getString("analysis_rule_version")), generationId.toString()).stream().findFirst()
                .map(identity -> new AudiobookBookAnalysisView(identity.id(),
                        analysisProvenance(identity.modelVersion()), analysisProvenance(identity.ruleVersion()),
                        analysisCharacters(identity.id()), analysisRelationships(identity.id()),
                        analysisSpeechSegments(identity.id())));
    }

    /**
     * 查询一代有声书的冻结音色计划。
     *
     * @param generationId 生成运行标识
     * @return 音色计划视图
     */
    public Optional<AudiobookVoicePlanView> findVoicePlan(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id, voice_quality_gate_attestation_sha256
                FROM audiobook_generation WHERE id = ? AND voice_plan_fingerprint_sha256 IS NOT NULL
                """, (resultSet, rowNumber) -> new VoicePlanViewSource(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("voice_quality_gate_attestation_sha256")),
                generationId.toString()).stream().findFirst()
                .map(value -> new AudiobookVoicePlanView(value.id(), value.qualityGateAttestationSha256(),
                        voiceBindings(value.id())));
    }

    /**
     * 查询所有已启用、可供审核界面选择的音色目录项。
     *
     * @param generationId 已存在的生成版本标识
     * @return 不含供应商密钥的目录视图
     */
    public Optional<AudiobookVoiceCatalogView> findVoiceCatalog(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")), generationId.toString())
                .stream().findFirst().map(id -> new AudiobookVoiceCatalogView(id, enabledVoiceCatalog()));
    }

    /**
     * 查询一个生成版本已冻结的读音词典；没有词条时返回稳定的空词典摘要。
     *
     * @param generationId 生成运行标识
     * @return 不含正文或供应商认证信息的词典视图
     */
    public Optional<AudiobookPronunciationDictionaryView> findPronunciationDictionary(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id, pronunciation_fingerprint_sha256 FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new PronunciationDictionaryIdentity(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("pronunciation_fingerprint_sha256")),
                generationId.toString()).stream().findFirst().map(identity ->
                new AudiobookPronunciationDictionaryView(identity.id(),
                        identity.fingerprint() == null ? EMPTY_PRONUNCIATION_FINGERPRINT : identity.fingerprint(),
                        pronunciationEntries(identity.id())));
    }

    /**
     * 返回读音修订执行器计算精确受影响章节所需的冻结输入。
     *
     * @param generationId 读音修订版本标识
     * @return 受控章节快照与需匹配词条
     */
    public Optional<AudiobookPronunciationPreparationInput> findPronunciationPreparationInput(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id, revision_pronunciation_term FROM audiobook_generation
                WHERE id = ? AND pronunciation_task_instance_id IS NOT NULL
                  AND current_stage = 'PREPARING_PRONUNCIATION'
                  AND revision_pronunciation_term IS NOT NULL
                """, (resultSet, rowNumber) -> new PronunciationPreparationIdentity(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("revision_pronunciation_term")),
                generationId.toString()).stream().findFirst().map(identity ->
                new AudiobookPronunciationPreparationInput(identity.id(), identity.term(),
                        pronunciationPreparationChapters(identity.id())));
    }

    /**
     * 查询当前所有者可试听的一条已审核音色样音内部定位信息。
     *
     * @param generationId 有声书生成运行标识
     * @param ownerId 所有者标识
     * @param provider 音色供应商标识
     * @param voiceType 音色标识
     * @return 样音内部定位信息
     */
    public Optional<AudiobookVoicePreview> findVoicePreview(UUID generationId, long ownerId, String provider,
                                                            String voiceType) {
        return jdbcTemplate.query("""
                SELECT generation.id, catalog.provider, catalog.voice_type, catalog.preview_storage_uri,
                       catalog.preview_size_bytes, catalog.preview_format
                FROM audiobook_generation generation
                JOIN audiobook_voice_catalog catalog ON catalog.enabled = TRUE
                WHERE generation.id = ? AND generation.owner_id = ? AND catalog.provider = ?
                  AND catalog.voice_type = ? AND catalog.preview_storage_uri IS NOT NULL
                  AND catalog.preview_content_sha256 IS NOT NULL AND catalog.preview_size_bytes IS NOT NULL
                  AND catalog.preview_format IS NOT NULL AND catalog.preview_duration_ms IS NOT NULL
                """, (resultSet, rowNumber) -> new AudiobookVoicePreview(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("provider"),
                resultSet.getString("voice_type"), resultSet.getString("preview_storage_uri"),
                resultSet.getLong("preview_size_bytes"), resultSet.getString("preview_format")),
                generationId.toString(), ownerId, provider, voiceType).stream().findFirst();
    }

    /**
     * 幂等保存一次全书人物、别名、关系和说话人分析结果。
     *
     * @param generationId 生成运行标识
     * @param request 受限分析结果
     * @return 已保存实体数量摘要
     */
    public AudiobookBookAnalysisResult saveBookAnalysis(UUID generationId,
                                                        AudiobookBookAnalysisBatchRequest request) {
        String analysisModelVersion = normalizedAnalysisProvenance(request.analysisModelVersion(), 128);
        String analysisRuleVersion = normalizedAnalysisProvenance(request.analysisRuleVersion(), 64);
        AnalysisFingerprint existing = jdbcTemplate.query("""
                SELECT analysis_fingerprint_sha256, analysis_model_version, analysis_rule_version
                FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new AnalysisFingerprint(
                resultSet.getString("analysis_fingerprint_sha256"), resultSet.getString("analysis_model_version"),
                resultSet.getString("analysis_rule_version")), generationId.toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("audiobook generation does not exist"));
        if (existing.value() != null) {
            if (!existing.value().equalsIgnoreCase(request.analysisFingerprintSha256())
                    || !java.util.Objects.equals(existing.modelVersion(), analysisModelVersion)
                    || !java.util.Objects.equals(existing.ruleVersion(), analysisRuleVersion)) {
                throw new IllegalStateException("audiobook book analysis conflicts with frozen result");
            }
            return analysisResult(generationId);
        }
        ensureProjectedChapters(generationId);
        java.util.Map<String, UUID> characters = insertCharacters(generationId, request.characters());
        insertRelationships(generationId, request.relationships(), characters);
        insertSpeechSegments(generationId, request.speechSegments(), characters);
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation_chapter
                SET analysis_status = 'READY', error_code = NULL, updated_at = ?
                WHERE generation_id = ?
                """, Timestamp.from(now), generationId.toString());
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET analysis_fingerprint_sha256 = ?, analysis_model_version = ?, analysis_rule_version = ?,
                    status = 'ANALYSIS_READY', current_stage = 'ANALYSIS_READY',
                    error_code = NULL, updated_at = ?
                WHERE id = ?
                """, request.analysisFingerprintSha256().toLowerCase(), analysisModelVersion,
                analysisRuleVersion, Timestamp.from(now), generationId.toString());
        return analysisResult(generationId);
    }

    /**
     * 校验全书分析已持久化并返回可审计的数量摘要。
     *
     * @param generationId 生成运行标识
     * @return 已保存实体数量摘要
     */
    public AudiobookBookAnalysisResult completeBookAnalysis(UUID generationId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter WHERE generation_id = ?
                """, Integer.class, generationId.toString());
        Integer ready = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter
                WHERE generation_id = ? AND analysis_status = 'READY'
                """, Integer.class, generationId.toString());
        String fingerprint = jdbcTemplate.query("""
                SELECT analysis_fingerprint_sha256 FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> resultSet.getString("analysis_fingerprint_sha256"),
                generationId.toString()).stream().findFirst().orElse(null);
        if (fingerprint == null || total == null || total == 0 || !total.equals(ready)) {
            throw new IllegalStateException("audiobook book analysis is incomplete");
        }
        return analysisResult(generationId);
    }

    /**
     * 幂等保存一个包含旁白和全部角色的冻结音色计划。
     *
     * @param generationId 生成运行标识
     * @param request 音色目录与绑定计划
     * @return 已保存绑定数量摘要
     */
    public AudiobookVoicePlanResult saveVoicePlan(UUID generationId, AudiobookVoicePlanRequest request) {
        VoicePlanFingerprint existing = jdbcTemplate.query("""
                SELECT voice_plan_fingerprint_sha256, voice_quality_gate_attestation_sha256
                FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new VoicePlanFingerprint(
                resultSet.getString("voice_plan_fingerprint_sha256"),
                resultSet.getString("voice_quality_gate_attestation_sha256")),
                generationId.toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("audiobook generation does not exist"));
        if (existing.value() != null) {
            if (!existing.value().equalsIgnoreCase(request.voicePlanFingerprintSha256())
                    || !java.util.Objects.equals(existing.qualityGateAttestationSha256(),
                    normalizedQualityGateAttestation(request.qualityGateAttestationSha256()))) {
                throw new IllegalStateException("audiobook voice plan conflicts with frozen result");
            }
            return voicePlanResult(generationId);
        }
        ensureBookAnalysisCompleted(generationId);
        java.util.Map<String, VoiceCatalogEntry> voices = upsertVoices(request.voices());
        java.util.Map<String, UUID> characters = characterIds(generationId);
        validateVoiceBindings(request.bindings(), voices, characters);
        Instant now = Instant.now();
        for (AudiobookVoicePlanRequest.Binding binding : request.bindings()) {
            VoiceCatalogEntry voice = voices.get(voiceKey(binding.provider(), binding.voiceType()));
            UUID characterId = binding.characterCanonicalName() == null ? null
                    : characters.get(canonicalName(binding.characterCanonicalName()));
            jdbcTemplate.update("""
                    INSERT INTO audiobook_voice_binding
                        (id, generation_id, role_key, character_id, voice_catalog_id, provider, voice_type,
                         ssml_supported, match_score, match_method, rationale_tags_json, locked, review_status,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTO', ?, ?)
                    """, UUID.randomUUID().toString(), generationId.toString(), binding.roleKey(),
                    characterId == null ? null : characterId.toString(), voice.id().toString(), binding.provider().trim(),
                    binding.voiceType().trim(), voice.ssmlSupported(), binding.matchScore(), binding.matchMethod().trim(),
                    json(binding.rationaleTags()), binding.locked(), Timestamp.from(now), Timestamp.from(now));
        }
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET voice_plan_fingerprint_sha256 = ?, voice_quality_gate_attestation_sha256 = ?,
                    status = 'VOICE_PLAN_READY', current_stage = 'VOICE_PLAN_READY',
                    error_code = NULL, updated_at = ?
                WHERE id = ?
                """, request.voicePlanFingerprintSha256().toLowerCase(),
                normalizedQualityGateAttestation(request.qualityGateAttestationSha256()), Timestamp.from(now),
                generationId.toString());
        return voicePlanResult(generationId);
    }

    /**
     * 校验全书音色计划已冻结，并返回绑定数量摘要。
     *
     * @param generationId 生成运行标识
     * @return 已冻结绑定数量摘要
     */
    public AudiobookVoicePlanResult completeVoicePlan(UUID generationId) {
        VoicePlanFingerprint fingerprint = jdbcTemplate.query("""
                SELECT voice_plan_fingerprint_sha256 FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new VoicePlanFingerprint(
                resultSet.getString("voice_plan_fingerprint_sha256"), null), generationId.toString()).stream().findFirst()
                .orElse(null);
        int characterCount = count("audiobook_character_profile", generationId);
        int bindingCount = count("audiobook_voice_binding", generationId);
        if (fingerprint == null || fingerprint.value() == null || bindingCount != characterCount + 1) {
            throw new IllegalStateException("audiobook voice plan is incomplete");
        }
        return voicePlanResult(generationId);
    }

    /**
     * 查询一个生成版本的播放清单，不返回底层存储地址。
     *
     * @param generationId 生成运行标识
     * @return 播放清单
     */
    public Optional<AudiobookPlaybackManifest> findPlaybackManifest(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id, generation_version, status FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new PlaybackGeneration(
                UUID.fromString(resultSet.getString("id")), resultSet.getInt("generation_version"),
                resultSet.getString("status")), generationId.toString()).stream().findFirst()
                .map(generation -> new AudiobookPlaybackManifest(generation.id(), generation.version(),
                        generation.status(), playbackChapters(generation.id())));
    }

    /**
     * 查询一个所有者可播放的章节音频内部定位信息。
     *
     * @param generationId 生成运行标识
     * @param ownerId 所有者标识
     * @param chapterIndex 章节序号
     * @return 可播放章节音频
     */
    public Optional<AudiobookAudioChapter> findPlayableChapter(UUID generationId, long ownerId, int chapterIndex) {
        return jdbcTemplate.query("""
                SELECT generation.id, chapter.chapter_index, chapter.audio_storage_uri, chapter.audio_size_bytes,
                       chapter.audio_format
                FROM audiobook_generation generation
                JOIN audiobook_generation_chapter chapter ON chapter.generation_id = generation.id
                WHERE generation.id = ? AND generation.owner_id = ? AND chapter.chapter_index = ?
                  AND chapter.synthesis_status = 'READY' AND chapter.audio_storage_uri IS NOT NULL
                  AND chapter.audio_size_bytes IS NOT NULL AND chapter.audio_format IS NOT NULL
                """, (resultSet, rowNumber) -> new AudiobookAudioChapter(
                UUID.fromString(resultSet.getString("id")), resultSet.getInt("chapter_index"),
                resultSet.getString("audio_storage_uri"), resultSet.getLong("audio_size_bytes"),
                resultSet.getString("audio_format")), generationId.toString(), ownerId, chapterIndex)
                .stream().findFirst();
    }

    /**
     * 查询当前运行之前、同一书籍谱系最近完成的生成版本。
     *
     * @param bookLineageKey 书籍谱系标识
     * @param generationVersion 当前生成版本号
     * @return 上一完成生成运行标识
     */
    public Optional<UUID> findPreviousCompletedGeneration(String bookLineageKey, int generationVersion) {
        return jdbcTemplate.query("""
                SELECT id FROM audiobook_generation
                WHERE book_lineage_key = ? AND generation_version < ? AND status = 'COMPLETED'
                ORDER BY generation_version DESC LIMIT 1
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")),
                bookLineageKey, generationVersion).stream().findFirst();
    }

    /**
     * 查询已冻结的章节快照，用于与上一完成版本制定增量计划。
     *
     * @param generationId 生成运行标识
     * @return 有序章节快照
     */
    public List<AudiobookChapterSnapshot> chapterSnapshots(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT chapter_index, chapter_identity_sha256, chapter_content_sha256, chapter_title
                FROM audiobook_generation_chapter
                WHERE generation_id = ? AND source_status = 'READY'
                ORDER BY chapter_index
                """, (resultSet, rowNumber) -> new AudiobookChapterSnapshot(
                resultSet.getInt("chapter_index"), resultSet.getString("chapter_identity_sha256"),
                resultSet.getString("chapter_content_sha256"), resultSet.getString("chapter_title")),
                generationId.toString());
    }

    /**
     * 写入增量章节变更类型，并为可复用章节复制不可变音频资产引用。
     *
     * @param generationId 当前生成运行标识
     * @param previousGenerationId 上一已完成运行标识，可为空
     * @param plans 当前章节处理计划
     * @param reusableAnalysis 是否可安全继承未变章节的分析事实
     * @return 已复用音频章节数
     */
    public int applyIncrementalPlan(UUID generationId, UUID previousGenerationId,
                                    List<AudiobookChapterPlan> plans, boolean reusableAnalysis) {
        Instant now = Instant.now();
        int reused = 0;
        for (AudiobookChapterPlan plan : plans) {
            int index = plan.chapter().chapterIndex();
            jdbcTemplate.update("""
                    UPDATE audiobook_generation_chapter
                    SET change_type = ?, reused_from_generation_id = ?, reused_from_chapter_index = ?,
                        updated_at = ?
                    WHERE generation_id = ? AND chapter_index = ?
                    """, plan.change().name(), plan.reusable() && previousGenerationId != null
                    ? previousGenerationId.toString() : null, plan.reusable() ? plan.previousChapterIndex() : null,
                    Timestamp.from(now), generationId.toString(), index);
            if (!plan.reusable() || previousGenerationId == null || plan.previousChapterIndex() == null) {
                continue;
            }
            ReusableAudio audio = jdbcTemplate.query("""
                    SELECT audio_asset_id, audio_storage_uri, audio_content_sha256, audio_size_bytes,
                           audio_format, duration_ms
                    FROM audiobook_generation_chapter
                    WHERE generation_id = ? AND chapter_index = ? AND synthesis_status = 'READY'
                    """, (resultSet, rowNumber) -> new ReusableAudio(
                    UUID.fromString(resultSet.getString("audio_asset_id")), resultSet.getString("audio_storage_uri"),
                    resultSet.getString("audio_content_sha256"), resultSet.getLong("audio_size_bytes"),
                    resultSet.getString("audio_format"), resultSet.getLong("duration_ms")),
                    previousGenerationId.toString(), plan.previousChapterIndex()).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("audiobook reusable chapter audio is unavailable"));
            ExistingReusableAudio existing = jdbcTemplate.query("""
                    SELECT synthesis_status, audio_asset_id FROM audiobook_generation_chapter
                    WHERE generation_id = ? AND chapter_index = ?
                    """, (resultSet, rowNumber) -> new ExistingReusableAudio(
                    resultSet.getString("synthesis_status"), resultSet.getString("audio_asset_id")),
                    generationId.toString(), index).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("audiobook chapter is not in generation"));
            if ("READY".equals(existing.synthesisStatus())) {
                if (!audio.assetRegistryId().toString().equals(existing.assetRegistryId())) {
                    throw new IllegalStateException("audiobook reusable chapter audio conflicts with frozen result");
                }
                reused++;
                continue;
            }
            jdbcTemplate.update("""
                    INSERT INTO audiobook_audio_asset
                        (id, generation_id, chapter_index, asset_registry_id, storage_uri, content_sha256, format,
                         size_bytes, duration_ms, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), generationId.toString(), index, audio.assetRegistryId().toString(),
                    audio.storageUri(), audio.contentSha256(), audio.format(), audio.sizeBytes(), audio.durationMs(),
                    Timestamp.from(now), Timestamp.from(now));
            jdbcTemplate.update("""
                    UPDATE audiobook_generation_chapter
                    SET analysis_status = ?, synthesis_status = 'READY', audio_asset_id = ?,
                        audio_storage_uri = ?, audio_content_sha256 = ?, audio_size_bytes = ?, audio_format = ?,
                        duration_ms = ?, error_code = NULL, updated_at = ?
                    WHERE generation_id = ? AND chapter_index = ?
                    """, reusableAnalysis ? "READY" : "PENDING", audio.assetRegistryId().toString(),
                    audio.storageUri(), audio.contentSha256(),
                    audio.sizeBytes(), audio.format(), audio.durationMs(), Timestamp.from(now),
                    generationId.toString(), index);
            reused++;
        }
        return reused;
    }

    /**
     * 返回当前已就绪章节音频数量。
     *
     * @param generationId 生成运行标识
     * @return 已就绪章节数
     */
    public int readySynthesisCount(UUID generationId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter
                WHERE generation_id = ? AND synthesis_status = 'READY'
                """, Integer.class, generationId.toString());
        return count == null ? 0 : count;
    }

    /**
     * 返回当前生成章节总数。
     *
     * @param generationId 生成运行标识
     * @return 章节总数
     */
    public int chapterCount(UUID generationId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter WHERE generation_id = ?
                """, Integer.class, generationId.toString());
        return count == null ? 0 : count;
    }

    /**
     * 幂等写入一批已发布、已校验的章节音频资产。
     *
     * @param generationId 生成运行标识
     * @param request 章节音频资产批次
     * @return 写入章节数量
     */
    public int saveSynthesisBatch(UUID generationId, AudiobookSynthesisBatchRequest request) {
        Instant now = Instant.now();
        int saved = 0;
        for (AudiobookSynthesisBatchRequest.Chapter chapter : request.chapters()) {
            SynthesisState existing = jdbcTemplate.query("""
                    SELECT source_status, chapter_content_sha256, synthesis_status, audio_asset_id,
                           audio_storage_uri, audio_content_sha256
                    FROM audiobook_generation_chapter
                    WHERE generation_id = ? AND chapter_index = ?
                    """, (resultSet, rowNumber) -> new SynthesisState(resultSet.getString("source_status"),
                    resultSet.getString("chapter_content_sha256"), resultSet.getString("synthesis_status"),
                    resultSet.getString("audio_asset_id"), resultSet.getString("audio_storage_uri"),
                    resultSet.getString("audio_content_sha256")), generationId.toString(), chapter.index())
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("audiobook chapter is not in generation"));
            if (!"READY".equals(existing.sourceStatus())
                    || !chapter.sourceContentSha256().equals(existing.sourceContentSha256())) {
                throw new IllegalStateException("audiobook synthesis source does not match frozen text");
            }
            if ("READY".equals(existing.synthesisStatus())
                    && (!chapter.storageUri().equals(existing.storageUri())
                    || !chapter.contentSha256().equals(existing.contentSha256()))) {
                throw new IllegalStateException("audiobook chapter synthesis conflicts with frozen result");
            }
            if (existing.audioAssetId() != null && !existing.audioAssetId().equals(chapter.assetId().toString())) {
                throw new IllegalStateException("audiobook chapter audio asset conflicts with frozen result");
            }
            UUID audioAssetId = chapter.assetId();
            if (existing.audioAssetId() == null) {
                jdbcTemplate.update("""
                        INSERT INTO audiobook_audio_asset
                            (id, generation_id, chapter_index, asset_registry_id, storage_uri, content_sha256,
                             format, size_bytes, duration_ms, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), generationId.toString(), chapter.index(),
                        audioAssetId.toString(), chapter.storageUri(), chapter.contentSha256(), chapter.format(),
                        chapter.sizeBytes(), chapter.durationMs(), Timestamp.from(now), Timestamp.from(now));
            }
            jdbcTemplate.update("""
                    UPDATE audiobook_generation_chapter
                    SET synthesis_status = 'READY', audio_asset_id = ?, audio_storage_uri = ?,
                        audio_content_sha256 = ?, audio_size_bytes = ?, audio_format = ?, duration_ms = ?,
                        error_code = NULL, updated_at = ?
                    WHERE generation_id = ? AND chapter_index = ?
                    """, audioAssetId.toString(), chapter.storageUri(), chapter.contentSha256(), chapter.sizeBytes(),
                    chapter.format(), chapter.durationMs(), Timestamp.from(now), generationId.toString(),
                    chapter.index());
            saved++;
        }
        return saved;
    }

    /**
     * 在所有章节音频都就绪后完成有声书运行。
     *
     * @param generationId 生成运行标识
     * @return 已合成章节数
     */
    public int completeSynthesis(UUID generationId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter WHERE generation_id = ?
                """, Integer.class, generationId.toString());
        Integer ready = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter
                WHERE generation_id = ? AND synthesis_status = 'READY'
                """, Integer.class, generationId.toString());
        if (total == null || total == 0 || !total.equals(ready)) {
            throw new IllegalStateException("audiobook synthesis is incomplete");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET status = 'COMPLETED', current_stage = 'COMPLETED', completed_chapter_count = ?,
                    failed_chapter_count = 0, error_code = NULL, finished_at = ?, updated_at = ?
                WHERE id = ?
                """, ready, Timestamp.from(now), Timestamp.from(now), generationId.toString());
        return ready;
    }

    /**
     * 根据受限执行器对冻结正文的精确匹配结果失效章节音频，并推进读音修订到合成阶段。
     *
     * @param generationId 读音修订版本标识
     * @param chapterIndexes 精确命中词条的章节序号
     * @return 已重新排队的章节数
     */
    public int applyPronunciationMatches(UUID generationId, List<Integer> chapterIndexes) {
        if (chapterIndexes == null || chapterIndexes.size() > 20_000) {
            throw new IllegalArgumentException("audiobook pronunciation affected chapters are invalid");
        }
        java.util.Set<Integer> expected = chapterIndexes(generationId);
        java.util.Set<Integer> affected = new java.util.LinkedHashSet<>();
        for (Integer chapterIndex : chapterIndexes) {
            if (chapterIndex == null || !expected.contains(chapterIndex) || !affected.add(chapterIndex)) {
                throw new IllegalArgumentException("audiobook pronunciation affected chapters are invalid");
            }
        }
        String stage = jdbcTemplate.query("""
                SELECT current_stage FROM audiobook_generation
                WHERE id = ? AND pronunciation_task_instance_id IS NOT NULL
                  AND revision_pronunciation_term IS NOT NULL
                """, (resultSet, rowNumber) -> resultSet.getString("current_stage"), generationId.toString())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                        "audiobook pronunciation revision task is unavailable"));
        if (!"PREPARING_PRONUNCIATION".equals(stage)) {
            throw new IllegalStateException("audiobook pronunciation revision is not awaiting affected chapters");
        }
        Instant now = Instant.now();
        for (int chapterIndex : affected) {
            jdbcTemplate.update("""
                    DELETE FROM audiobook_audio_asset WHERE generation_id = ? AND chapter_index = ?
                    """, generationId.toString(), chapterIndex);
            jdbcTemplate.update("""
                    UPDATE audiobook_generation_chapter
                    SET change_type = 'PRONUNCIATION_REPAIR', reused_from_generation_id = NULL,
                        reused_from_chapter_index = NULL, synthesis_status = 'PENDING', audio_asset_id = NULL,
                        audio_storage_uri = NULL, audio_content_sha256 = NULL, audio_size_bytes = NULL,
                        audio_format = NULL, duration_ms = NULL, error_code = NULL, updated_at = ?
                    WHERE generation_id = ? AND chapter_index = ?
                    """, Timestamp.from(now), generationId.toString(), chapterIndex);
        }
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET pronunciation_task_instance_id = NULL, status = 'VOICE_PLAN_READY',
                    current_stage = 'VOICE_PLAN_READY', error_code = NULL,
                    updated_at = ?
                WHERE id = ?
                """, Timestamp.from(now), generationId.toString());
        return affected.size();
    }

    /**
     * 将任务调度终态同步到生成运行。
     *
     * @param generationId 生成运行标识
     * @param status 调度任务状态
     * @param errorCode 稳定错误码
     */
    public void updateTerminalTaskStatus(UUID generationId, String status, String errorCode) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE audiobook_generation
                SET status = ?, current_stage = ?, error_code = ?, finished_at = ?, updated_at = ?
                WHERE id = ? AND status <> 'TEXT_READY'
                """, status, status, errorCode, Timestamp.from(now), Timestamp.from(now), generationId.toString());
    }

    private FrozenGeneration frozenGeneration(UUID generationId) {
        FrozenGeneration result = jdbcTemplate.query("""
                SELECT analysis_fingerprint_sha256, voice_plan_fingerprint_sha256,
                       voice_quality_gate_attestation_sha256, analysis_model_version,
                       analysis_rule_version, pronunciation_fingerprint_sha256
                FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> new FrozenGeneration(
                resultSet.getString("analysis_fingerprint_sha256"),
                resultSet.getString("voice_plan_fingerprint_sha256"),
                resultSet.getString("voice_quality_gate_attestation_sha256"), resultSet.getString("analysis_model_version"),
                resultSet.getString("analysis_rule_version"),
                resultSet.getString("pronunciation_fingerprint_sha256")), generationId.toString())
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("audiobook generation does not exist"));
        if (result.analysisFingerprint() == null || result.voicePlanFingerprint() == null) {
            throw new IllegalStateException("audiobook revision requires frozen analysis and voice plan");
        }
        return result;
    }

    private EnabledVoice enabledVoice(String provider, String voiceType) {
        return jdbcTemplate.query("""
                SELECT id, narrator_eligible, ssml_supported FROM audiobook_voice_catalog
                WHERE provider = ? AND voice_type = ? AND enabled = TRUE
                """, (resultSet, rowNumber) -> new EnabledVoice(UUID.fromString(resultSet.getString("id")),
                provider, voiceType, resultSet.getBoolean("narrator_eligible"),
                resultSet.getBoolean("ssml_supported")), provider, voiceType).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("audiobook voice revision voice is not enabled"));
    }

    private Set<Integer> affectedChapters(UUID generationId, VoiceBindingSnapshot binding, String roleKey) {
        if ("NARRATOR".equals(roleKey)) {
            return new java.util.LinkedHashSet<>(jdbcTemplate.query("""
                    SELECT chapter_index FROM audiobook_generation_chapter
                    WHERE generation_id = ? ORDER BY chapter_index
                    """, (resultSet, rowNumber) -> resultSet.getInt("chapter_index"), generationId.toString()));
        }
        if (binding.characterId() == null) {
            throw new IllegalStateException("audiobook character voice binding is corrupted");
        }
        return new java.util.LinkedHashSet<>(jdbcTemplate.query("""
                SELECT DISTINCT chapter_index FROM audiobook_speech_attribution
                WHERE generation_id = ? AND speaker_character_id = ? ORDER BY chapter_index
                """, (resultSet, rowNumber) -> resultSet.getInt("chapter_index"), generationId.toString(),
                binding.characterId().toString()));
    }

    private SourceSpeechAttribution sourceSpeechAttribution(UUID generationId, int chapterIndex, int sequenceNumber) {
        return jdbcTemplate.query("""
                SELECT chapter_index, sequence_number, text_start_codepoint, text_end_codepoint, speaker_kind,
                       speaker_character_id, delivery_tags_json, evidence_excerpt, confidence, review_status
                FROM audiobook_speech_attribution
                WHERE generation_id = ? AND chapter_index = ? AND sequence_number = ?
                """, (resultSet, rowNumber) -> new SourceSpeechAttribution(resultSet.getInt("chapter_index"),
                resultSet.getInt("sequence_number"), resultSet.getInt("text_start_codepoint"),
                resultSet.getInt("text_end_codepoint"), resultSet.getString("speaker_kind"),
                resultSet.getString("speaker_character_id"), resultSet.getString("delivery_tags_json"),
                resultSet.getString("evidence_excerpt"), resultSet.getDouble("confidence"),
                resultSet.getString("review_status")), generationId.toString(),
                chapterIndex, sequenceNumber).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("audiobook speaker revision segment does not exist"));
    }

    private SpeakerRevisionTarget speakerRevisionTarget(UUID generationId,
                                                        CreateAudiobookSpeakerRevisionRequest request) {
        String kind = request.speakerKind().trim();
        String canonicalName = normalizedSpeakerCanonicalName(request);
        if ("CHARACTER".equals(kind)) {
            if (canonicalName == null) {
                throw new IllegalArgumentException("audiobook character speaker revision requires a character");
            }
            String characterId = jdbcTemplate.query("""
                    SELECT id FROM audiobook_character_profile WHERE generation_id = ? AND canonical_name = ?
                    """, (resultSet, rowNumber) -> resultSet.getString("id"), generationId.toString(), canonicalName)
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "audiobook speaker revision character does not exist"));
            return new SpeakerRevisionTarget(kind, canonicalName, characterId);
        }
        if (canonicalName != null) {
            throw new IllegalArgumentException("audiobook non-character speaker revision cannot name a character");
        }
        return new SpeakerRevisionTarget(kind, null, null);
    }

    private String normalizedSpeakerCanonicalName(CreateAudiobookSpeakerRevisionRequest request) {
        if (request.speakerCanonicalName() == null) {
            return null;
        }
        String value = request.speakerCanonicalName().trim();
        return value.isEmpty() ? null : value;
    }

    private void copyChapters(UUID parentGenerationId, UUID revisionId, Instant now, String reusedChangeType) {
        jdbcTemplate.update("""
                INSERT INTO audiobook_generation_chapter
                    (generation_id, chapter_index, chapter_identity_sha256, chapter_content_sha256, chapter_title,
                     source_resource_ref, source_start_offset, source_end_offset, source_text_uri,
                     source_text_size_bytes, source_status, analysis_status, synthesis_status, audio_asset_id,
                     timing_asset_id, audio_storage_uri, audio_content_sha256, audio_size_bytes, audio_format,
                     duration_ms, retry_count, error_code, change_type, reused_from_generation_id,
                     reused_from_chapter_index, created_at, updated_at)
                SELECT ?, chapter_index, chapter_identity_sha256, chapter_content_sha256, chapter_title,
                       source_resource_ref, source_start_offset, source_end_offset, source_text_uri,
                       source_text_size_bytes, source_status, analysis_status, synthesis_status, audio_asset_id,
                       timing_asset_id, audio_storage_uri, audio_content_sha256, audio_size_bytes, audio_format,
                       duration_ms, retry_count, NULL, ?, ?, chapter_index, ?, ?
                FROM audiobook_generation_chapter WHERE generation_id = ?
                """, revisionId.toString(), reusedChangeType, parentGenerationId.toString(), Timestamp.from(now),
                Timestamp.from(now), parentGenerationId.toString());
    }

    private void copyAudioAssets(UUID parentGenerationId, UUID revisionId, Instant now) {
        List<SourceAudioAsset> values = jdbcTemplate.query("""
                SELECT chapter_index, asset_registry_id, storage_uri, content_sha256, format, size_bytes, duration_ms
                FROM audiobook_audio_asset WHERE generation_id = ? ORDER BY chapter_index
                """, (resultSet, rowNumber) -> new SourceAudioAsset(resultSet.getInt("chapter_index"),
                UUID.fromString(resultSet.getString("asset_registry_id")), resultSet.getString("storage_uri"),
                resultSet.getString("content_sha256"), resultSet.getString("format"),
                resultSet.getLong("size_bytes"), resultSet.getLong("duration_ms")), parentGenerationId.toString());
        for (SourceAudioAsset value : values) {
            jdbcTemplate.update("""
                    INSERT INTO audiobook_audio_asset
                        (id, generation_id, chapter_index, asset_registry_id, storage_uri, content_sha256, format,
                         size_bytes, duration_ms, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), revisionId.toString(), value.chapterIndex(),
                    value.assetRegistryId().toString(), value.storageUri(), value.contentSha256(), value.format(),
                    value.sizeBytes(), value.durationMs(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    private Map<UUID, UUID> copyCharacters(UUID parentGenerationId, UUID revisionId, Instant now) {
        List<SourceCharacter> values = jdbcTemplate.query("""
                SELECT id, canonical_name, display_name, presentation, character_type, traits_json,
                       first_chapter_index, occurrence_count, confidence, review_status
                FROM audiobook_character_profile WHERE generation_id = ? ORDER BY canonical_name
                """, (resultSet, rowNumber) -> new SourceCharacter(UUID.fromString(resultSet.getString("id")),
                resultSet.getString("canonical_name"), resultSet.getString("display_name"),
                resultSet.getString("presentation"), resultSet.getString("character_type"),
                resultSet.getString("traits_json"), resultSet.getInt("first_chapter_index"),
                resultSet.getInt("occurrence_count"), resultSet.getDouble("confidence"),
                resultSet.getString("review_status")), parentGenerationId.toString());
        Map<UUID, UUID> identifiers = new LinkedHashMap<>();
        for (SourceCharacter value : values) {
            UUID characterId = UUID.randomUUID();
            identifiers.put(value.id(), characterId);
            jdbcTemplate.update("""
                    INSERT INTO audiobook_character_profile
                        (id, generation_id, canonical_name, display_name, presentation, character_type, traits_json,
                         first_chapter_index, occurrence_count, confidence, review_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, characterId.toString(), revisionId.toString(), value.canonicalName(), value.displayName(),
                    value.presentation(), value.characterType(), normalizedJson(value.traitsJson()),
                    value.firstChapterIndex(),
                    value.occurrenceCount(), value.confidence(), value.reviewStatus(), Timestamp.from(now),
                    Timestamp.from(now));
            copyAliases(value.id(), characterId, now);
        }
        return identifiers;
    }

    private void copyAliases(UUID sourceCharacterId, UUID revisionCharacterId, Instant now) {
        List<SourceAlias> values = jdbcTemplate.query("""
                SELECT alias, alias_type, evidence_chapter_index, evidence_start_codepoint, evidence_end_codepoint,
                       confidence FROM audiobook_character_alias WHERE character_id = ? ORDER BY alias
                """, (resultSet, rowNumber) -> new SourceAlias(resultSet.getString("alias"),
                resultSet.getString("alias_type"), resultSet.getObject("evidence_chapter_index", Integer.class),
                resultSet.getObject("evidence_start_codepoint", Integer.class),
                resultSet.getObject("evidence_end_codepoint", Integer.class), resultSet.getDouble("confidence")),
                sourceCharacterId.toString());
        for (SourceAlias value : values) {
            jdbcTemplate.update("""
                    INSERT INTO audiobook_character_alias
                        (id, character_id, alias, alias_type, evidence_chapter_index, evidence_start_codepoint,
                         evidence_end_codepoint, confidence, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), revisionCharacterId.toString(), value.alias(),
                    value.aliasType(), value.evidenceChapterIndex(), value.evidenceStartCodepoint(),
                    value.evidenceEndCodepoint(), value.confidence(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    private void copyRelationships(UUID parentGenerationId, UUID revisionId, Map<UUID, UUID> characterIds,
                                   Instant now) {
        List<SourceRelationship> values = jdbcTemplate.query("""
                SELECT source_character_id, target_character_id, relationship_type, direction,
                       evidence_chapter_index, evidence_start_codepoint, evidence_end_codepoint, confidence,
                       review_status
                FROM audiobook_character_relationship WHERE generation_id = ?
                """, (resultSet, rowNumber) -> new SourceRelationship(
                UUID.fromString(resultSet.getString("source_character_id")),
                UUID.fromString(resultSet.getString("target_character_id")), resultSet.getString("relationship_type"),
                resultSet.getString("direction"), resultSet.getObject("evidence_chapter_index", Integer.class),
                resultSet.getObject("evidence_start_codepoint", Integer.class),
                resultSet.getObject("evidence_end_codepoint", Integer.class), resultSet.getDouble("confidence"),
                resultSet.getString("review_status")), parentGenerationId.toString());
        for (SourceRelationship value : values) {
            jdbcTemplate.update("""
                    INSERT INTO audiobook_character_relationship
                        (id, generation_id, source_character_id, target_character_id, relationship_type, direction,
                         evidence_chapter_index, evidence_start_codepoint, evidence_end_codepoint, confidence,
                         review_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), revisionId.toString(),
                    requiredCharacterId(characterIds, value.sourceCharacterId()).toString(),
                    requiredCharacterId(characterIds, value.targetCharacterId()).toString(), value.relationshipType(),
                    value.direction(), value.evidenceChapterIndex(), value.evidenceStartCodepoint(),
                    value.evidenceEndCodepoint(), value.confidence(), value.reviewStatus(), Timestamp.from(now),
                    Timestamp.from(now));
        }
    }

    private void copySpeechAttributions(UUID parentGenerationId, UUID revisionId, Map<UUID, UUID> characterIds,
                                        Instant now) {
        List<SourceSpeechAttribution> values = jdbcTemplate.query("""
                SELECT chapter_index, sequence_number, text_start_codepoint, text_end_codepoint, speaker_kind,
                       speaker_character_id, delivery_tags_json, evidence_excerpt, confidence, review_status
                FROM audiobook_speech_attribution WHERE generation_id = ? ORDER BY chapter_index, sequence_number
                """, (resultSet, rowNumber) -> new SourceSpeechAttribution(resultSet.getInt("chapter_index"),
                resultSet.getInt("sequence_number"), resultSet.getInt("text_start_codepoint"),
                resultSet.getInt("text_end_codepoint"), resultSet.getString("speaker_kind"),
                resultSet.getString("speaker_character_id"), resultSet.getString("delivery_tags_json"),
                resultSet.getString("evidence_excerpt"), resultSet.getDouble("confidence"),
                resultSet.getString("review_status")), parentGenerationId.toString());
        for (SourceSpeechAttribution value : values) {
            UUID sourceCharacterId = value.speakerCharacterId() == null ? null
                    : UUID.fromString(value.speakerCharacterId());
            UUID revisionCharacterId = sourceCharacterId == null ? null
                    : requiredCharacterId(characterIds, sourceCharacterId);
            jdbcTemplate.update("""
                    INSERT INTO audiobook_speech_attribution
                        (id, generation_id, chapter_index, sequence_number, text_start_codepoint,
                         text_end_codepoint, speaker_kind, speaker_character_id, delivery_tags_json, evidence_excerpt,
                         confidence, review_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), revisionId.toString(), value.chapterIndex(),
                    value.sequenceNumber(), value.startCodepoint(), value.endCodepoint(), value.speakerKind(),
                    revisionCharacterId == null ? null : revisionCharacterId.toString(),
                    normalizedJson(value.deliveryTagsJson()), normalizedEvidenceExcerpt(value.evidenceExcerpt()),
                    value.confidence(), value.reviewStatus(),
                    Timestamp.from(now), Timestamp.from(now));
        }
    }

    private void copyVoiceBindings(UUID revisionId, List<VoiceBindingSnapshot> sourceBindings,
                                   Map<UUID, UUID> characterIds, String revisedRoleKey, EnabledVoice selectedVoice,
                                   Instant now) {
        for (VoiceBindingSnapshot value : sourceBindings) {
            boolean revised = revisedRoleKey != null && revisedRoleKey.equals(value.roleKey());
            UUID characterId = value.characterId() == null ? null : requiredCharacterId(characterIds, value.characterId());
            jdbcTemplate.update("""
                    INSERT INTO audiobook_voice_binding
                        (id, generation_id, role_key, character_id, voice_catalog_id, provider, voice_type,
                         ssml_supported, match_score, match_method, rationale_tags_json, locked, review_status,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), revisionId.toString(), value.roleKey(),
                    characterId == null ? null : characterId.toString(),
                    (revised ? selectedVoice.id() : value.voiceCatalogId()).toString(),
                    revised ? selectedVoice.provider() : value.provider(),
                    revised ? selectedVoice.voiceType() : value.voiceType(),
                    revised ? selectedVoice.ssmlSupported() : value.ssmlSupported(), revised ? 1.0 : value.matchScore(),
                    revised ? "MANUAL_V1" : value.matchMethod(),
                    revised ? json(List.of("manual_override")) : normalizedJson(value.rationaleTagsJson()),
                    revised || value.locked(), revised ? "MANUAL" : value.reviewStatus(), Timestamp.from(now),
                    Timestamp.from(now));
        }
    }

    private void copyPronunciations(UUID parentGenerationId, UUID revisionId, Instant now) {
        for (AudiobookPronunciationDictionaryView.Entry value : pronunciationEntries(parentGenerationId)) {
            jdbcTemplate.update("""
                    INSERT INTO audiobook_pronunciation_entry
                        (id, generation_id, term, pinyin, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), revisionId.toString(), value.term(), value.pinyin(),
                    Timestamp.from(now), Timestamp.from(now));
        }
    }

    private void upsertPronunciation(UUID generationId, String term, String pinyin, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE audiobook_pronunciation_entry SET pinyin = ?, updated_at = ?
                WHERE generation_id = ? AND term = ?
                """, pinyin, Timestamp.from(now), generationId.toString(), term);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO audiobook_pronunciation_entry
                        (id, generation_id, term, pinyin, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), generationId.toString(), term, pinyin,
                    Timestamp.from(now), Timestamp.from(now));
        }
    }

    private String pronunciation(UUID generationId, String term) {
        return jdbcTemplate.query("""
                SELECT pinyin FROM audiobook_pronunciation_entry
                WHERE generation_id = ? AND term = ?
                """, (resultSet, rowNumber) -> resultSet.getString("pinyin"), generationId.toString(), term)
                .stream().findFirst().orElse(null);
    }

    private UUID requiredCharacterId(Map<UUID, UUID> identifiers, UUID sourceCharacterId) {
        UUID result = identifiers.get(sourceCharacterId);
        if (result == null) {
            throw new IllegalStateException("audiobook revision character reference is corrupted");
        }
        return result;
    }

    private String voiceRevisionFingerprint(String sourceFingerprint, String roleKey, String provider,
                                            String voiceType) {
        try {
            String value = sourceFingerprint + "\n" + roleKey + "\n" + provider + "\n" + voiceType;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String speakerRevisionFingerprint(String sourceFingerprint, CreateAudiobookSpeakerRevisionRequest request,
                                              String canonicalName) {
        try {
            String value = sourceFingerprint + "\nSPEAKER_REVIEW_V1\n" + request.chapterIndex() + "\n"
                    + request.sequenceNumber() + "\n" + request.speakerKind().trim() + "\n"
                    + (canonicalName == null ? "" : canonicalName);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String pronunciationRevisionFingerprint(String sourceFingerprint, String term, String pinyin) {
        try {
            String value = sourceFingerprint + "\nPRONUNCIATION_REVIEW_V1\n" + term + "\n" + pinyin;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private List<CatalogEntry> catalog(UUID importRequestId) {
        return jdbcTemplate.query("""
                SELECT entry_index, title, resource_ref, start_offset, end_offset FROM ebook_catalog_entry
                WHERE import_request_id = ? ORDER BY entry_index
                """, (resultSet, rowNumber) -> new CatalogEntry(resultSet.getInt("entry_index"),
                resultSet.getString("title"), resultSet.getString("resource_ref"),
                resultSet.getObject("start_offset", Long.class), resultSet.getObject("end_offset", Long.class)),
                importRequestId.toString());
    }

    private List<AudiobookTextProjectionInput.Chapter> projectionChapters(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT chapter_index, chapter_title, source_resource_ref, source_start_offset, source_end_offset
                FROM audiobook_generation_chapter
                WHERE generation_id = ? ORDER BY chapter_index
                """, (resultSet, rowNumber) -> new AudiobookTextProjectionInput.Chapter(
                resultSet.getInt("chapter_index"), resultSet.getString("chapter_title"),
                resultSet.getString("source_resource_ref"), resultSet.getObject("source_start_offset", Long.class),
                resultSet.getObject("source_end_offset", Long.class)), generationId.toString());
    }

    private List<AudiobookSynthesisInput.Chapter> synthesisChapters(UUID generationId, String narratorProvider,
                                                                      String narratorVoiceType,
                                                                      boolean narratorSsmlSupported) {
        return jdbcTemplate.query("""
                SELECT chapter_index, chapter_title, chapter_content_sha256, source_text_uri, source_text_size_bytes
                FROM audiobook_generation_chapter
                WHERE generation_id = ? AND source_status = 'READY' AND synthesis_status <> 'READY'
                ORDER BY chapter_index
                """, (resultSet, rowNumber) -> new AudiobookSynthesisInput.Chapter(
                resultSet.getInt("chapter_index"), resultSet.getString("chapter_title"),
                resultSet.getString("chapter_content_sha256"), resultSet.getString("source_text_uri"),
                resultSet.getLong("source_text_size_bytes"), synthesisSegments(generationId,
                resultSet.getInt("chapter_index"), narratorProvider, narratorVoiceType, narratorSsmlSupported)),
                generationId.toString());
    }

    private List<AudiobookSynthesisInput.Segment> synthesisSegments(UUID generationId, int chapterIndex,
                                                                      String narratorProvider, String narratorVoiceType,
                                                                      boolean narratorSsmlSupported) {
        return jdbcTemplate.query("""
                SELECT segment.text_start_codepoint, segment.text_end_codepoint, segment.speaker_kind, segment.confidence,
                       COALESCE(binding.provider, ?) AS provider, COALESCE(binding.voice_type, ?) AS voice_type,
                       COALESCE(binding.ssml_supported, ?) AS ssml_supported
                FROM audiobook_speech_attribution segment
                LEFT JOIN audiobook_voice_binding binding ON binding.generation_id = segment.generation_id
                    AND binding.character_id = segment.speaker_character_id
                WHERE segment.generation_id = ? AND segment.chapter_index = ?
                ORDER BY segment.sequence_number
                """, (resultSet, rowNumber) -> new AudiobookSynthesisInput.Segment(
                resultSet.getInt("text_start_codepoint"), resultSet.getInt("text_end_codepoint"),
                resultSet.getString("provider"), resultSet.getString("voice_type"),
                resultSet.getBoolean("ssml_supported"),
                resultSet.getString("speaker_kind"), resultSet.getDouble("confidence")), narratorProvider,
                narratorVoiceType, narratorSsmlSupported, generationId.toString(),
                chapterIndex);
    }

    private List<AudiobookSynthesisInput.Pronunciation> pronunciations(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT term, pinyin FROM audiobook_pronunciation_entry
                WHERE generation_id = ? ORDER BY CHAR_LENGTH(term) DESC, term
                """, (resultSet, rowNumber) -> new AudiobookSynthesisInput.Pronunciation(
                resultSet.getString("term"), resultSet.getString("pinyin")), generationId.toString());
    }

    private List<AudiobookPronunciationDictionaryView.Entry> pronunciationEntries(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT term, pinyin FROM audiobook_pronunciation_entry
                WHERE generation_id = ? ORDER BY term
                """, (resultSet, rowNumber) -> new AudiobookPronunciationDictionaryView.Entry(
                resultSet.getString("term"), resultSet.getString("pinyin")), generationId.toString());
    }

    private List<AudiobookPronunciationPreparationInput.Chapter> pronunciationPreparationChapters(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT chapter_index, chapter_content_sha256, source_text_uri, source_text_size_bytes
                FROM audiobook_generation_chapter
                WHERE generation_id = ? AND source_status = 'READY'
                ORDER BY chapter_index
                """, (resultSet, rowNumber) -> new AudiobookPronunciationPreparationInput.Chapter(
                resultSet.getInt("chapter_index"), resultSet.getString("chapter_content_sha256"),
                resultSet.getString("source_text_uri"), resultSet.getLong("source_text_size_bytes")),
                generationId.toString());
    }

    private List<AudiobookBookAnalysisInput.Chapter> analysisChapters(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT chapter_index, chapter_title, chapter_content_sha256, source_text_uri, source_text_size_bytes
                FROM audiobook_generation_chapter
                WHERE generation_id = ? AND source_status = 'READY' AND analysis_status = 'PENDING'
                ORDER BY chapter_index
                """, (resultSet, rowNumber) -> new AudiobookBookAnalysisInput.Chapter(
                resultSet.getInt("chapter_index"), resultSet.getString("chapter_title"),
                resultSet.getString("chapter_content_sha256"), resultSet.getString("source_text_uri"),
                resultSet.getLong("source_text_size_bytes")), generationId.toString());
    }

    private AudiobookBookAnalysisInput.InheritedAnalysis inheritedAnalysis(UUID generationId) {
        List<ReusableAnalysisChapter> reused = jdbcTemplate.query("""
                SELECT chapter_index, reused_from_generation_id, reused_from_chapter_index
                FROM audiobook_generation_chapter
                WHERE generation_id = ? AND analysis_status = 'READY'
                  AND reused_from_generation_id IS NOT NULL AND reused_from_chapter_index IS NOT NULL
                ORDER BY chapter_index
                """, (resultSet, rowNumber) -> new ReusableAnalysisChapter(resultSet.getInt("chapter_index"),
                UUID.fromString(resultSet.getString("reused_from_generation_id")),
                resultSet.getInt("reused_from_chapter_index")), generationId.toString());
        if (reused.isEmpty()) {
            return AudiobookBookAnalysisInput.InheritedAnalysis.empty();
        }
        UUID sourceGenerationId = reused.getFirst().sourceGenerationId();
        Map<Integer, Integer> chapterIndexes = new LinkedHashMap<>();
        for (ReusableAnalysisChapter chapter : reused) {
            if (!sourceGenerationId.equals(chapter.sourceGenerationId())
                    || chapterIndexes.putIfAbsent(chapter.sourceChapterIndex(), chapter.currentChapterIndex()) != null) {
                throw new IllegalStateException("audiobook reusable analysis mapping is invalid");
            }
        }
        AudiobookBookAnalysisView source = findBookAnalysis(sourceGenerationId)
                .orElseThrow(() -> new IllegalStateException("audiobook reusable analysis is unavailable"));
        return new AudiobookBookAnalysisInput.InheritedAnalysis(
                inheritedCharacters(source.characters(), chapterIndexes),
                inheritedRelationships(source.relationships(), chapterIndexes),
                inheritedSpeechSegments(source.speechSegments(), chapterIndexes));
    }

    private List<AudiobookBookAnalysisInput.Character> inheritedCharacters(
            List<AudiobookBookAnalysisView.Character> values, Map<Integer, Integer> chapterIndexes) {
        return values.stream().map(value -> new AudiobookBookAnalysisInput.Character(value.canonicalName(),
                value.displayName(), value.presentation(), value.characterType(), value.traits(),
                mappedChapterIndex(value.firstChapterIndex(), chapterIndexes), value.occurrenceCount(),
                value.confidence(), inheritedAliases(value.aliases(), chapterIndexes))).toList();
    }

    private List<AudiobookBookAnalysisInput.Alias> inheritedAliases(List<AudiobookBookAnalysisView.Alias> values,
                                                                      Map<Integer, Integer> chapterIndexes) {
        return values.stream().filter(value -> value.evidenceChapterIndex() != null
                && value.evidenceStartCodepoint() != null && value.evidenceEndCodepoint() != null)
                .map(value -> new AudiobookBookAnalysisInput.Alias(value.alias(), value.aliasType(),
                        mappedChapterIndex(value.evidenceChapterIndex(), chapterIndexes),
                        value.evidenceStartCodepoint(), value.evidenceEndCodepoint(), value.confidence())).toList();
    }

    private List<AudiobookBookAnalysisInput.Relationship> inheritedRelationships(
            List<AudiobookBookAnalysisView.Relationship> values, Map<Integer, Integer> chapterIndexes) {
        return values.stream().filter(value -> value.evidenceChapterIndex() != null
                && value.evidenceStartCodepoint() != null && value.evidenceEndCodepoint() != null)
                .map(value -> new AudiobookBookAnalysisInput.Relationship(value.sourceCanonicalName(),
                        value.targetCanonicalName(), value.relationshipType(), value.direction(),
                        mappedChapterIndex(value.evidenceChapterIndex(), chapterIndexes),
                        value.evidenceStartCodepoint(), value.evidenceEndCodepoint(), value.confidence())).toList();
    }

    private List<AudiobookBookAnalysisInput.SpeechSegment> inheritedSpeechSegments(
            List<AudiobookBookAnalysisView.SpeechSegment> values, Map<Integer, Integer> chapterIndexes) {
        return values.stream().map(value -> new AudiobookBookAnalysisInput.SpeechSegment(
                mappedChapterIndex(value.chapterIndex(), chapterIndexes), value.sequenceNumber(),
                value.textStartCodepoint(), value.textEndCodepoint(), value.speakerKind(),
                value.speakerCanonicalName(), value.deliveryTags(), value.confidence(), value.evidenceExcerpt())).toList();
    }

    private int mappedChapterIndex(int sourceChapterIndex, Map<Integer, Integer> chapterIndexes) {
        Integer currentChapterIndex = chapterIndexes.get(sourceChapterIndex);
        if (currentChapterIndex == null) {
            throw new IllegalStateException("audiobook reusable analysis chapter mapping is incomplete");
        }
        return currentChapterIndex;
    }

    private List<AudiobookVoiceMatchInput.Character> voiceMatchCharacters(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT canonical_name, presentation, character_type, traits_json, confidence
                FROM audiobook_character_profile WHERE generation_id = ? ORDER BY canonical_name
                """, (resultSet, rowNumber) -> new AudiobookVoiceMatchInput.Character(
                resultSet.getString("canonical_name"), resultSet.getString("presentation"),
                resultSet.getString("character_type"), tags(resultSet.getString("traits_json")),
                resultSet.getDouble("confidence")), generationId.toString());
    }

    private List<AudiobookVoiceMatchInput.LockedBinding> inheritedVoiceBindings(UUID generationId) {
        List<UUID> sourceGenerationIds = jdbcTemplate.query("""
                SELECT DISTINCT reused_from_generation_id
                FROM audiobook_generation_chapter
                WHERE generation_id = ? AND analysis_status = 'READY'
                  AND reused_from_generation_id IS NOT NULL
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("reused_from_generation_id")),
                generationId.toString());
        if (sourceGenerationIds.isEmpty()) {
            return List.of();
        }
        if (sourceGenerationIds.size() != 1) {
            throw new IllegalStateException("audiobook reusable voice source is invalid");
        }
        return jdbcTemplate.query("""
                SELECT binding.role_key, character.canonical_name, binding.provider, binding.voice_type,
                       binding.match_score, binding.match_method, binding.rationale_tags_json
                FROM audiobook_voice_binding binding
                LEFT JOIN audiobook_character_profile character ON character.id = binding.character_id
                WHERE binding.generation_id = ?
                ORDER BY binding.role_key
                """, (resultSet, rowNumber) -> new AudiobookVoiceMatchInput.LockedBinding(
                resultSet.getString("role_key"), resultSet.getString("canonical_name"),
                resultSet.getString("provider"), resultSet.getString("voice_type"),
                resultSet.getDouble("match_score"), resultSet.getString("match_method"),
                tags(resultSet.getString("rationale_tags_json"))), sourceGenerationIds.getFirst().toString());
    }

    private List<AudiobookVoiceMatchInput.Voice> enabledVoices() {
        return jdbcTemplate.query("""
                SELECT provider, voice_type, language, presentation, age_group, style_tags_json, narrator_eligible,
                       catalog_version, ssml_supported FROM audiobook_voice_catalog WHERE enabled = TRUE
                ORDER BY provider, voice_type
                """, (resultSet, rowNumber) -> new AudiobookVoiceMatchInput.Voice(
                resultSet.getString("provider"), resultSet.getString("voice_type"), resultSet.getString("language"),
                resultSet.getString("presentation"), resultSet.getString("age_group"),
                tags(resultSet.getString("style_tags_json")), resultSet.getBoolean("narrator_eligible"),
                resultSet.getString("catalog_version"), resultSet.getBoolean("ssml_supported")));
    }

    private List<AudiobookVoiceCatalogView.Voice> enabledVoiceCatalog() {
        return jdbcTemplate.query("""
                SELECT provider, voice_type, language, presentation, age_group, style_tags_json, narrator_eligible,
                       catalog_version, ssml_supported,
                       preview_storage_uri IS NOT NULL AND preview_content_sha256 IS NOT NULL
                           AND preview_size_bytes IS NOT NULL AND preview_format IS NOT NULL
                           AND preview_duration_ms IS NOT NULL AS preview_available
                FROM audiobook_voice_catalog WHERE enabled = TRUE
                ORDER BY provider, voice_type
                """, (resultSet, rowNumber) -> new AudiobookVoiceCatalogView.Voice(
                resultSet.getString("provider"), resultSet.getString("voice_type"),
                resultSet.getString("language"), resultSet.getString("presentation"),
                resultSet.getString("age_group"), tags(resultSet.getString("style_tags_json")),
                resultSet.getBoolean("narrator_eligible"), resultSet.getString("catalog_version"),
                resultSet.getBoolean("ssml_supported"),
                resultSet.getBoolean("preview_available")));
    }

    private void validateVoicePreview(AudiobookVoicePlanRequest.Voice value) {
        boolean configured = value.previewStorageUri() != null || value.previewContentSha256() != null
                || value.previewSizeBytes() != null || value.previewFormat() != null || value.previewDurationMs() != null;
        if (!configured) {
            return;
        }
        if (value.previewStorageUri() == null || value.previewContentSha256() == null || value.previewSizeBytes() == null
                || value.previewFormat() == null || value.previewDurationMs() == null
                || !value.previewStorageUri().matches("^storage://[A-Za-z0-9][A-Za-z0-9._-]{0,127}/.+$")
                || value.previewStorageUri().contains("..")
                || !value.previewContentSha256().matches("^[a-fA-F0-9]{64}$")
                || value.previewSizeBytes() < 1 || value.previewDurationMs() < 1
                || !AUDIO_PREVIEW_FORMATS.contains(value.previewFormat().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("audiobook voice preview is invalid");
        }
    }

    private List<AudiobookVoicePlanView.Binding> voiceBindings(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT binding.role_key, character.canonical_name, binding.provider, binding.voice_type,
                       binding.match_score, binding.match_method, binding.rationale_tags_json, binding.locked,
                       binding.review_status
                FROM audiobook_voice_binding binding
                LEFT JOIN audiobook_character_profile character ON character.id = binding.character_id
                WHERE binding.generation_id = ? ORDER BY binding.role_key
                """, (resultSet, rowNumber) -> new AudiobookVoicePlanView.Binding(
                resultSet.getString("role_key"), resultSet.getString("canonical_name"),
                resultSet.getString("provider"), resultSet.getString("voice_type"),
                resultSet.getDouble("match_score"), resultSet.getString("match_method"),
                tags(resultSet.getString("rationale_tags_json")), resultSet.getBoolean("locked"),
                resultSet.getString("review_status")), generationId.toString());
    }

    private List<VoiceBindingSnapshot> voiceBindingSnapshots(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT role_key, character_id, voice_catalog_id, provider, voice_type, ssml_supported, match_score, match_method,
                       rationale_tags_json, locked, review_status
                FROM audiobook_voice_binding WHERE generation_id = ? ORDER BY role_key
                """, (resultSet, rowNumber) -> {
            String characterId = resultSet.getString("character_id");
            return new VoiceBindingSnapshot(resultSet.getString("role_key"),
                    characterId == null ? null : UUID.fromString(characterId),
                    UUID.fromString(resultSet.getString("voice_catalog_id")), resultSet.getString("provider"),
                    resultSet.getString("voice_type"), resultSet.getBoolean("ssml_supported"),
                    resultSet.getDouble("match_score"),
                    resultSet.getString("match_method"), resultSet.getString("rationale_tags_json"),
                    resultSet.getBoolean("locked"), resultSet.getString("review_status"));
        }, generationId.toString());
    }

    private List<AudiobookBookAnalysisView.Character> analysisCharacters(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT id, canonical_name, display_name, presentation, character_type, traits_json,
                       first_chapter_index, occurrence_count, confidence, review_status
                FROM audiobook_character_profile WHERE generation_id = ?
                ORDER BY first_chapter_index, canonical_name
                """, (resultSet, rowNumber) -> new AudiobookBookAnalysisView.Character(
                resultSet.getString("canonical_name"), resultSet.getString("display_name"),
                resultSet.getString("presentation"), resultSet.getString("character_type"),
                tags(resultSet.getString("traits_json")), resultSet.getInt("first_chapter_index"),
                resultSet.getInt("occurrence_count"), resultSet.getDouble("confidence"),
                resultSet.getString("review_status"), analysisAliases(UUID.fromString(resultSet.getString("id")))),
                generationId.toString());
    }

    private List<AudiobookBookAnalysisView.Alias> analysisAliases(UUID characterId) {
        return jdbcTemplate.query("""
                SELECT alias, alias_type, evidence_chapter_index, evidence_start_codepoint, evidence_end_codepoint,
                       confidence FROM audiobook_character_alias WHERE character_id = ? ORDER BY alias
                """, (resultSet, rowNumber) -> new AudiobookBookAnalysisView.Alias(
                resultSet.getString("alias"), resultSet.getString("alias_type"),
                resultSet.getObject("evidence_chapter_index", Integer.class),
                resultSet.getObject("evidence_start_codepoint", Integer.class),
                resultSet.getObject("evidence_end_codepoint", Integer.class), resultSet.getDouble("confidence")),
                characterId.toString());
    }

    private List<AudiobookBookAnalysisView.Relationship> analysisRelationships(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT source.canonical_name AS source_name, target.canonical_name AS target_name,
                       relationship.relationship_type, relationship.direction, relationship.evidence_chapter_index,
                       relationship.evidence_start_codepoint, relationship.evidence_end_codepoint,
                       relationship.confidence, relationship.review_status
                FROM audiobook_character_relationship relationship
                JOIN audiobook_character_profile source ON source.id = relationship.source_character_id
                JOIN audiobook_character_profile target ON target.id = relationship.target_character_id
                WHERE relationship.generation_id = ?
                ORDER BY source.canonical_name, target.canonical_name, relationship.relationship_type
                """, (resultSet, rowNumber) -> new AudiobookBookAnalysisView.Relationship(
                resultSet.getString("source_name"), resultSet.getString("target_name"),
                resultSet.getString("relationship_type"), resultSet.getString("direction"),
                resultSet.getObject("evidence_chapter_index", Integer.class),
                resultSet.getObject("evidence_start_codepoint", Integer.class),
                resultSet.getObject("evidence_end_codepoint", Integer.class), resultSet.getDouble("confidence"),
                resultSet.getString("review_status")), generationId.toString());
    }

    private List<AudiobookBookAnalysisView.SpeechSegment> analysisSpeechSegments(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT segment.chapter_index, segment.sequence_number, segment.text_start_codepoint,
                       segment.text_end_codepoint, segment.speaker_kind, character.canonical_name,
                       segment.delivery_tags_json, segment.confidence, segment.review_status, segment.evidence_excerpt
                FROM audiobook_speech_attribution segment
                LEFT JOIN audiobook_character_profile character ON character.id = segment.speaker_character_id
                WHERE segment.generation_id = ?
                ORDER BY segment.chapter_index, segment.sequence_number
                """, (resultSet, rowNumber) -> new AudiobookBookAnalysisView.SpeechSegment(
                resultSet.getInt("chapter_index"), resultSet.getInt("sequence_number"),
                resultSet.getInt("text_start_codepoint"), resultSet.getInt("text_end_codepoint"),
                resultSet.getString("speaker_kind"), resultSet.getString("canonical_name"),
                tags(resultSet.getString("delivery_tags_json")), resultSet.getDouble("confidence"),
                resultSet.getString("review_status"), resultSet.getString("evidence_excerpt")), generationId.toString());
    }

    private List<AudiobookPlaybackManifest.Chapter> playbackChapters(UUID generationId) {
        return jdbcTemplate.query("""
                SELECT chapter_index, chapter_title, synthesis_status, audio_asset_id, duration_ms
                FROM audiobook_generation_chapter
                WHERE generation_id = ? ORDER BY chapter_index
                """, (resultSet, rowNumber) -> {
            String assetId = resultSet.getString("audio_asset_id");
            return new AudiobookPlaybackManifest.Chapter(resultSet.getInt("chapter_index"),
                    resultSet.getString("chapter_title"), resultSet.getString("synthesis_status"),
                    assetId == null ? null : UUID.fromString(assetId),
                    resultSet.getObject("duration_ms", Long.class));
        }, generationId.toString());
    }

    private void ensureProjectedChapters(UUID generationId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter WHERE generation_id = ?
                """, Integer.class, generationId.toString());
        Integer ready = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter
                WHERE generation_id = ? AND source_status = 'READY'
                """, Integer.class, generationId.toString());
        if (total == null || total == 0 || !total.equals(ready)) {
            throw new IllegalStateException("audiobook book analysis source is incomplete");
        }
    }

    private void ensureBookAnalysisCompleted(UUID generationId) {
        String fingerprint = jdbcTemplate.query("""
                SELECT analysis_fingerprint_sha256 FROM audiobook_generation WHERE id = ?
                """, (resultSet, rowNumber) -> resultSet.getString("analysis_fingerprint_sha256"),
                generationId.toString()).stream().findFirst().orElse(null);
        if (fingerprint == null) {
            throw new IllegalStateException("audiobook book analysis is incomplete");
        }
    }

    private java.util.Map<String, VoiceCatalogEntry> upsertVoices(List<AudiobookVoicePlanRequest.Voice> values) {
        java.util.Map<String, VoiceCatalogEntry> result = new java.util.LinkedHashMap<>();
        Instant now = Instant.now();
        for (AudiobookVoicePlanRequest.Voice value : values) {
            validateVoicePreview(value);
            String provider = value.provider().trim();
            String voiceType = value.voiceType().trim();
            String key = voiceKey(provider, voiceType);
            if (result.containsKey(key)) {
                throw new IllegalArgumentException("audiobook voice catalog entry is duplicated");
            }
            VoiceCatalogEntry existing = jdbcTemplate.query("""
                    SELECT id, provider, voice_type, ssml_supported FROM audiobook_voice_catalog
                    WHERE provider = ? AND voice_type = ?
                    """, (resultSet, rowNumber) -> new VoiceCatalogEntry(UUID.fromString(resultSet.getString("id")),
                    resultSet.getString("provider"), resultSet.getString("voice_type"),
                    resultSet.getBoolean("ssml_supported")), provider, voiceType)
                    .stream().findFirst().orElse(null);
            UUID id = existing == null ? UUID.randomUUID() : existing.id();
            if (existing == null) {
                jdbcTemplate.update("""
                        INSERT INTO audiobook_voice_catalog
                            (id, provider, voice_type, language, presentation, age_group, style_tags_json,
                             narrator_eligible, enabled, catalog_version, ssml_supported, preview_storage_uri,
                             preview_content_sha256, preview_size_bytes, preview_format, preview_duration_ms,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id.toString(), provider, voiceType, value.language().trim(), value.presentation(),
                        value.ageGroup().trim(), json(value.styleTags()), value.narratorEligible(),
                        value.catalogVersion().trim(), value.ssmlSupported(), value.previewStorageUri(),
                        value.previewContentSha256(),
                        value.previewSizeBytes(), value.previewFormat(), value.previewDurationMs(),
                        Timestamp.from(now), Timestamp.from(now));
            } else {
                jdbcTemplate.update("""
                        UPDATE audiobook_voice_catalog
                        SET language = ?, presentation = ?, age_group = ?, style_tags_json = ?, narrator_eligible = ?,
                            enabled = TRUE, catalog_version = ?, ssml_supported = ?, preview_storage_uri = ?,
                            preview_content_sha256 = ?,
                            preview_size_bytes = ?, preview_format = ?, preview_duration_ms = ?, updated_at = ?
                        WHERE id = ?
                        """, value.language().trim(), value.presentation(), value.ageGroup().trim(),
                        json(value.styleTags()), value.narratorEligible(), value.catalogVersion().trim(),
                        value.ssmlSupported(), value.previewStorageUri(), value.previewContentSha256(), value.previewSizeBytes(),
                        value.previewFormat(), value.previewDurationMs(), Timestamp.from(now), id.toString());
            }
            result.put(key, new VoiceCatalogEntry(id, provider, voiceType, value.ssmlSupported()));
        }
        return result;
    }

    private void validateVoiceBindings(List<AudiobookVoicePlanRequest.Binding> bindings,
                                       java.util.Map<String, VoiceCatalogEntry> voices,
                                       java.util.Map<String, UUID> characters) {
        java.util.Set<String> expected = new java.util.HashSet<>();
        expected.add("NARRATOR");
        for (String canonical : characters.keySet()) {
            expected.add("CHARACTER:" + canonical);
        }
        java.util.Set<String> actual = new java.util.HashSet<>();
        for (AudiobookVoicePlanRequest.Binding binding : bindings) {
            String role = binding.roleKey();
            if (!actual.add(role) || !expected.contains(role)
                    || !voices.containsKey(voiceKey(binding.provider().trim(), binding.voiceType().trim()))) {
                throw new IllegalArgumentException("audiobook voice binding is invalid");
            }
            if ("NARRATOR".equals(role)) {
                if (binding.characterCanonicalName() != null && !binding.characterCanonicalName().isBlank()) {
                    throw new IllegalArgumentException("audiobook narrator binding must not reference a character");
                }
            } else {
                String canonical = canonicalName(binding.characterCanonicalName());
                if (!role.equals("CHARACTER:" + canonical) || !characters.containsKey(canonical)) {
                    throw new IllegalArgumentException("audiobook character voice binding is invalid");
                }
            }
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("audiobook voice bindings are incomplete");
        }
    }

    private java.util.Map<String, UUID> characterIds(UUID generationId) {
        java.util.Map<String, UUID> result = new java.util.LinkedHashMap<>();
        List<CharacterIdentifier> rows = jdbcTemplate.query("""
                SELECT canonical_name, id FROM audiobook_character_profile WHERE generation_id = ? ORDER BY canonical_name
                """, (resultSet, rowNumber) -> new CharacterIdentifier(resultSet.getString("canonical_name"),
                UUID.fromString(resultSet.getString("id"))), generationId.toString());
        for (CharacterIdentifier row : rows) {
            result.put(row.canonicalName(), row.id());
        }
        return result;
    }

    private AudiobookVoicePlanResult voicePlanResult(UUID generationId) {
        return new AudiobookVoicePlanResult(generationId, count("audiobook_voice_binding", generationId));
    }

    /**
     * 规范化可选的多角色质量门禁证明摘要。
     *
     * @param value 执行器已校验的证明摘要
     * @return 小写摘要或空值
     */
    private String normalizedQualityGateAttestation(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private String voiceKey(String provider, String voiceType) {
        return provider + "\u0000" + voiceType;
    }

    private java.util.Map<String, UUID> insertCharacters(UUID generationId,
                                                           List<AudiobookBookAnalysisBatchRequest.Character> values) {
        java.util.Map<String, UUID> characters = new java.util.LinkedHashMap<>();
        java.util.Set<Integer> chapterIndexes = chapterIndexes(generationId);
        Instant now = Instant.now();
        for (AudiobookBookAnalysisBatchRequest.Character value : values) {
            String canonicalName = canonicalName(value.canonicalName());
            if (characters.containsKey(canonicalName)) {
                throw new IllegalArgumentException("audiobook character canonical name is duplicated");
            }
            if (!chapterIndexes.contains(value.firstChapterIndex())) {
                throw new IllegalArgumentException("audiobook character first chapter does not exist");
            }
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO audiobook_character_profile
                        (id, generation_id, canonical_name, display_name, presentation, character_type, traits_json,
                         first_chapter_index, occurrence_count, confidence, review_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTO', ?, ?)
                    """, id.toString(), generationId.toString(), canonicalName, value.displayName().trim(),
                    value.presentation(), value.characterType().trim(), json(value.traits()), value.firstChapterIndex(),
                    value.occurrenceCount(), value.confidence(), Timestamp.from(now), Timestamp.from(now));
            insertAliases(id, value.aliases(), chapterIndexes, now);
            characters.put(canonicalName, id);
        }
        return characters;
    }

    private void insertAliases(UUID characterId, List<AudiobookBookAnalysisBatchRequest.Alias> aliases,
                               java.util.Set<Integer> chapterIndexes, Instant now) {
        java.util.Set<String> uniqueAliases = new java.util.HashSet<>();
        for (AudiobookBookAnalysisBatchRequest.Alias alias : aliases) {
            String aliasValue = alias.alias().trim();
            if (!uniqueAliases.add(aliasValue)) {
                throw new IllegalArgumentException("audiobook character alias is duplicated");
            }
            validateEvidence(alias.evidenceChapterIndex(), alias.evidenceStartCodepoint(),
                    alias.evidenceEndCodepoint(), chapterIndexes);
            jdbcTemplate.update("""
                    INSERT INTO audiobook_character_alias
                        (id, character_id, alias, alias_type, evidence_chapter_index, evidence_start_codepoint,
                         evidence_end_codepoint, confidence, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), characterId.toString(), aliasValue, alias.aliasType().trim(),
                    alias.evidenceChapterIndex(), alias.evidenceStartCodepoint(), alias.evidenceEndCodepoint(),
                    alias.confidence(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    private void insertRelationships(UUID generationId,
                                     List<AudiobookBookAnalysisBatchRequest.Relationship> values,
                                     java.util.Map<String, UUID> characters) {
        java.util.Set<Integer> chapterIndexes = chapterIndexes(generationId);
        java.util.Set<String> uniqueRelationships = new java.util.HashSet<>();
        Instant now = Instant.now();
        for (AudiobookBookAnalysisBatchRequest.Relationship value : values) {
            String source = canonicalName(value.sourceCanonicalName());
            String target = canonicalName(value.targetCanonicalName());
            UUID sourceId = characters.get(source);
            UUID targetId = characters.get(target);
            if (sourceId == null || targetId == null) {
                throw new IllegalArgumentException("audiobook relationship character is unknown");
            }
            String relationshipType = value.relationshipType().trim();
            String direction = value.direction().trim();
            if (!uniqueRelationships.add(source + "\u0000" + target + "\u0000" + relationshipType + "\u0000" + direction)) {
                throw new IllegalArgumentException("audiobook relationship is duplicated");
            }
            validateEvidence(value.evidenceChapterIndex(), value.evidenceStartCodepoint(),
                    value.evidenceEndCodepoint(), chapterIndexes);
            jdbcTemplate.update("""
                    INSERT INTO audiobook_character_relationship
                        (id, generation_id, source_character_id, target_character_id, relationship_type, direction,
                         evidence_chapter_index, evidence_start_codepoint, evidence_end_codepoint, confidence,
                         review_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTO', ?, ?)
                    """, UUID.randomUUID().toString(), generationId.toString(), sourceId.toString(), targetId.toString(),
                    relationshipType, direction, value.evidenceChapterIndex(), value.evidenceStartCodepoint(),
                    value.evidenceEndCodepoint(), value.confidence(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    private void insertSpeechSegments(UUID generationId,
                                      List<AudiobookBookAnalysisBatchRequest.SpeechSegment> values,
                                      java.util.Map<String, UUID> characters) {
        java.util.Set<Integer> chapterIndexes = chapterIndexes(generationId);
        java.util.Set<String> sequences = new java.util.HashSet<>();
        java.util.Map<Integer, List<AudiobookBookAnalysisBatchRequest.SpeechSegment>> chapters =
                new java.util.HashMap<>();
        for (AudiobookBookAnalysisBatchRequest.SpeechSegment value : values) {
            if (!chapterIndexes.contains(value.chapterIndex())
                    || value.textEndCodepoint() <= value.textStartCodepoint()) {
                throw new IllegalArgumentException("audiobook speech segment range is invalid");
            }
            if (value.evidenceExcerpt() != null && value.confidence() >= SPEAKER_REVISION_MAXIMUM_CONFIDENCE) {
                throw new IllegalArgumentException("audiobook speech evidence requires low confidence");
            }
            normalizedEvidenceExcerpt(value.evidenceExcerpt());
            String sequence = value.chapterIndex() + ":" + value.sequenceNumber();
            if (!sequences.add(sequence)) {
                throw new IllegalArgumentException("audiobook speech segment sequence is duplicated");
            }
            if ("CHARACTER".equals(value.speakerKind())) {
                if (value.speakerCanonicalName() == null
                        || !characters.containsKey(canonicalName(value.speakerCanonicalName()))) {
                    throw new IllegalArgumentException("audiobook speech character is unknown");
                }
            } else if (value.speakerCanonicalName() != null && !value.speakerCanonicalName().isBlank()) {
                throw new IllegalArgumentException("audiobook non-character speech must not reference a character");
            }
            chapters.computeIfAbsent(value.chapterIndex(), ignored -> new java.util.ArrayList<>()).add(value);
        }
        for (List<AudiobookBookAnalysisBatchRequest.SpeechSegment> chapter : chapters.values()) {
            chapter.sort(java.util.Comparator.comparingInt(
                    AudiobookBookAnalysisBatchRequest.SpeechSegment::textStartCodepoint));
            int previousEnd = -1;
            for (AudiobookBookAnalysisBatchRequest.SpeechSegment value : chapter) {
                if (value.textStartCodepoint() < previousEnd) {
                    throw new IllegalArgumentException("audiobook speech segments overlap");
                }
                previousEnd = value.textEndCodepoint();
            }
        }
        Instant now = Instant.now();
        for (AudiobookBookAnalysisBatchRequest.SpeechSegment value : values) {
            UUID speakerId = "CHARACTER".equals(value.speakerKind())
                    ? characters.get(canonicalName(value.speakerCanonicalName())) : null;
            jdbcTemplate.update("""
                    INSERT INTO audiobook_speech_attribution
                        (id, generation_id, chapter_index, sequence_number, text_start_codepoint, text_end_codepoint,
                         speaker_kind, speaker_character_id, delivery_tags_json, evidence_excerpt, confidence, review_status,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTO', ?, ?)
                    """, UUID.randomUUID().toString(), generationId.toString(), value.chapterIndex(),
                    value.sequenceNumber(), value.textStartCodepoint(), value.textEndCodepoint(), value.speakerKind(),
                    speakerId == null ? null : speakerId.toString(), json(value.deliveryTags()),
                    normalizedEvidenceExcerpt(value.evidenceExcerpt()), value.confidence(),
                    Timestamp.from(now), Timestamp.from(now));
        }
    }

    private void validateEvidence(Integer chapterIndex, Integer startCodepoint, Integer endCodepoint,
                                  java.util.Set<Integer> chapterIndexes) {
        if (chapterIndex == null && startCodepoint == null && endCodepoint == null) {
            return;
        }
        if (chapterIndex == null || startCodepoint == null || endCodepoint == null
                || !chapterIndexes.contains(chapterIndex) || endCodepoint <= startCodepoint) {
            throw new IllegalArgumentException("audiobook analysis evidence is invalid");
        }
    }

    private java.util.Set<Integer> chapterIndexes(UUID generationId) {
        return new java.util.HashSet<>(jdbcTemplate.query("""
                SELECT chapter_index FROM audiobook_generation_chapter WHERE generation_id = ?
                """, (resultSet, rowNumber) -> resultSet.getInt("chapter_index"), generationId.toString()));
    }

    private AudiobookBookAnalysisResult analysisResult(UUID generationId) {
        return new AudiobookBookAnalysisResult(generationId, count("audiobook_character_profile", generationId),
                count("audiobook_character_relationship", generationId),
                count("audiobook_speech_attribution", generationId));
    }

    private int count(String table, UUID generationId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE generation_id = ?",
                Integer.class, generationId.toString());
        return count == null ? 0 : count;
    }

    private String canonicalName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("audiobook canonical name is blank");
        }
        return normalized;
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("audiobook analysis tags cannot be serialized", exception);
        }
    }

    private List<String> tags(String value) {
        try {
            var node = objectMapper.readTree(value);
            String payload = node.isTextual() ? node.textValue() : value;
            return objectMapper.readValue(payload, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("audiobook analysis tags are corrupted", exception);
        }
    }

    private String normalizedJson(String value) {
        return json(tags(value));
    }

    private String normalizedEvidenceExcerpt(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 768
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("audiobook speech evidence excerpt is invalid");
        }
        return normalized;
    }

    private String analysisProvenance(String value) {
        return value == null || value.isBlank() ? LEGACY_ANALYSIS_PROVENANCE : value;
    }

    private String normalizedAnalysisProvenance(String value, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException("audiobook analysis provenance is invalid");
        }
        return normalized;
    }

    private String bookLineageKey(long ownerId, UUID assetId, String metadataJson) {
        try {
            JsonNode metadata = objectMapper.readTree(metadataJson);
            if (metadata.isTextual()) {
                metadata = objectMapper.readTree(metadata.asText());
            }
            String mediaItemId = metadata.path("managedMediaItemId").asText();
            if (!mediaItemId.isBlank()) {
                // 受管媒体条目跨导入版本保持不变，作为增量比较的谱系边界。
                return "managed-media:" + ownerId + ":" + UUID.fromString(mediaItemId);
            }
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 非受管或历史资产无法提供受管媒体标识时，退化为资产自身的稳定边界。
            return "ebook-asset:" + assetId;
        }
        return "ebook-asset:" + assetId;
    }

    private Optional<AudiobookGenerationRecord> query(String condition, Object... arguments) {
        return jdbcTemplate.query("SELECT * FROM audiobook_generation " + condition, (resultSet, rowNumber) -> {
            String taskId = resultSet.getString("task_instance_id");
            String analysisTaskId = resultSet.getString("analysis_task_instance_id");
            String voiceMatchTaskId = resultSet.getString("voice_match_task_instance_id");
            String synthesisTaskId = resultSet.getString("synthesis_task_instance_id");
            String pronunciationTaskId = resultSet.getString("pronunciation_task_instance_id");
            Timestamp startedAt = resultSet.getTimestamp("started_at");
            Timestamp finishedAt = resultSet.getTimestamp("finished_at");
            return new AudiobookGenerationRecord(UUID.fromString(resultSet.getString("id")),
                    resultSet.getLong("owner_id"), UUID.fromString(resultSet.getString("ebook_asset_id")),
                    resultSet.getString("book_lineage_key"), resultSet.getString("idempotency_key"),
                    AudiobookGenerationMode.valueOf(resultSet.getString("mode")), resultSet.getString("status"),
                    resultSet.getString("current_stage"), resultSet.getString("book_content_sha256"),
                    resultSet.getInt("generation_version"), taskId == null ? null : UUID.fromString(taskId),
                    analysisTaskId == null ? null : UUID.fromString(analysisTaskId),
                    voiceMatchTaskId == null ? null : UUID.fromString(voiceMatchTaskId),
                    synthesisTaskId == null ? null : UUID.fromString(synthesisTaskId),
                    pronunciationTaskId == null ? null : UUID.fromString(pronunciationTaskId),
                    resultSet.getInt("requested_chapter_count"), resultSet.getInt("completed_chapter_count"),
                    resultSet.getInt("failed_chapter_count"), resultSet.getString("error_code"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    startedAt == null ? null : startedAt.toInstant(),
                    finishedAt == null ? null : finishedAt.toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        }, arguments).stream().findFirst();
    }

    private String chapterIdentity(String bookContentSha256, String resourceRef) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (bookContentSha256 + "\\n" + resourceRef).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 用于建立运行的受控电子书资产快照。
     *
     * @param id 电子书资产标识
     * @param importRequestId 导入请求标识
     * @param contentSha256 书籍内容摘要
     * @param chapterCount 导入阶段发现的章节数
     * @param storageUri 原始电子书受管存储地址
     */
    public record EbookAssetSnapshot(UUID id, UUID importRequestId, String contentSha256, int chapterCount,
                                     String storageUri, String bookLineageKey) {
    }

    private record CatalogEntry(int index, String title, String resourceRef, Long startOffset, Long endOffset) {
    }

    private record ReusableAudio(UUID assetRegistryId, String storageUri, String contentSha256, long sizeBytes,
                                 String format, long durationMs) {
    }

    private record ExistingReusableAudio(String synthesisStatus, String assetRegistryId) {
    }

    private record ReusableAnalysisChapter(int currentChapterIndex, UUID sourceGenerationId,
                                           int sourceChapterIndex) {
    }

    private record ProjectionSource(UUID generationId, String storageUri, String bookContentSha256) {
    }

    private record ProjectionState(String status, String contentSha256, String storageUri) {
    }

    /**
     * 已冻结正文的完整性和规模摘要。
     *
     * @param chapterCount 冻结章节总数
     * @param readyChapterCount 已冻结章节数
     * @param characterCount Unicode 码点数
     */
    public record ProjectionSummary(int chapterCount, int readyChapterCount, long characterCount) {
    }

    private record SynthesisState(String sourceStatus, String sourceContentSha256, String synthesisStatus,
                                  String audioAssetId, String storageUri, String contentSha256) {
    }

    private record PlaybackGeneration(UUID id, int version, String status) {
    }

    private record AnalysisFingerprint(String value, String modelVersion, String ruleVersion) {
    }

    private record AnalysisViewIdentity(UUID id, String modelVersion, String ruleVersion) {
    }

    private record PronunciationDictionaryIdentity(UUID id, String fingerprint) {
    }

    private record PronunciationPreparationIdentity(UUID id, String term) {
    }

    private record VoiceCatalogEntry(UUID id, String provider, String voiceType, boolean ssmlSupported) {
    }

    private record VoicePlanFingerprint(String value, String qualityGateAttestationSha256) {
    }

    private record VoicePlanViewSource(UUID id, String qualityGateAttestationSha256) {
    }

    private record CharacterIdentifier(String canonicalName, UUID id) {
    }

    private record SynthesisPlan(UUID generationId, String narratorProvider, String narratorVoiceType,
                                 boolean narratorSsmlSupported) {
    }

    private record FrozenGeneration(String analysisFingerprint, String voicePlanFingerprint,
                                    String qualityGateAttestationSha256, String analysisModelVersion,
                                    String analysisRuleVersion, String pronunciationFingerprint) {
    }

    private record EnabledVoice(UUID id, String provider, String voiceType, boolean narratorEligible,
                                boolean ssmlSupported) {
    }

    private record VoiceRevisionIdentity(String parentGenerationId, String roleKey, String provider,
                                         String voiceType, String mode) {
    }

    private record SpeakerRevisionIdentity(String parentGenerationId, Integer chapterIndex, Integer sequenceNumber,
                                           String speakerKind, String speakerCanonicalName, String mode) {
    }

    private record PronunciationRevisionIdentity(String parentGenerationId, String term, String pinyin, String mode) {
    }

    private record SpeakerRevisionTarget(String speakerKind, String canonicalName, String sourceCharacterId) {
    }

    private record SourceAudioAsset(int chapterIndex, UUID assetRegistryId, String storageUri,
                                    String contentSha256, String format, long sizeBytes, long durationMs) {
    }

    private record SourceCharacter(UUID id, String canonicalName, String displayName, String presentation,
                                   String characterType, String traitsJson, int firstChapterIndex,
                                   int occurrenceCount, double confidence, String reviewStatus) {
    }

    private record SourceAlias(String alias, String aliasType, Integer evidenceChapterIndex,
                               Integer evidenceStartCodepoint, Integer evidenceEndCodepoint, double confidence) {
    }

    private record SourceRelationship(UUID sourceCharacterId, UUID targetCharacterId, String relationshipType,
                                      String direction, Integer evidenceChapterIndex,
                                      Integer evidenceStartCodepoint, Integer evidenceEndCodepoint,
                                      double confidence, String reviewStatus) {
    }

    private record SourceSpeechAttribution(int chapterIndex, int sequenceNumber, int startCodepoint,
                                           int endCodepoint, String speakerKind, String speakerCharacterId,
                                           String deliveryTagsJson, String evidenceExcerpt, double confidence,
                                           String reviewStatus) {
    }

    private record VoiceBindingSnapshot(String roleKey, UUID characterId, UUID voiceCatalogId, String provider,
                                        String voiceType, boolean ssmlSupported, double matchScore, String matchMethod,
                                        String rationaleTagsJson, boolean locked, String reviewStatus) {
    }
}
