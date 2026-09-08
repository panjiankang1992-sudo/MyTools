package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.AudiobookGenerationMode;
import com.yuyutian.mytools.reader.model.AudiobookBookAnalysisBatchRequest;
import com.yuyutian.mytools.reader.model.AudiobookVoicePlanRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookVoiceRevisionRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookSpeakerRevisionRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookPronunciationRevisionRequest;
import com.yuyutian.mytools.reader.model.AudiobookTextProjectionBatchRequest;
import com.yuyutian.mytools.reader.model.CreateAudiobookGenerationRequest;
import com.yuyutian.mytools.reader.model.AudiobookExportFormat;
import com.yuyutian.mytools.reader.model.AudiobookExportResult;
import com.yuyutian.mytools.reader.model.CreateAudiobookExportRequest;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class AudiobookGenerationServiceTest {

    @Autowired
    private AudiobookGenerationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @Test
    void shouldCreateIdempotentGenerationAndFreezeChapterPlan() {
        long ownerId = 881L;
        UUID assetId = seedAsset(ownerId, 2);
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        var request = new CreateAudiobookGenerationRequest(ownerId, assetId, "audio-run-1",
                AudiobookGenerationMode.FULL, true);

        var created = service.create(request);
        var duplicate = service.create(request);
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "QUEUED", List.of()));
        Integer chapterCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter WHERE generation_id = ?
                """, Integer.class, created.id().toString());

        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(created.status()).isEqualTo("QUEUED");
        assertThat(created.currentStage()).isEqualTo("TEXT_EXTRACTING");
        assertThat(created.requestedChapterCount()).isEqualTo(2);
        assertThat(chapterCount).isEqualTo(2);
        assertThat(service.get(created.id(), ownerId).generationVersion()).isEqualTo(1);
        assertThatThrownBy(() -> service.get(created.id(), ownerId + 1))
                .isInstanceOf(AudiobookGenerationNotFoundException.class);
        assertThatThrownBy(() -> service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-1", AudiobookGenerationMode.INCREMENTAL, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectUnownedAssetAndUnconfirmedRights() {
        UUID assetId = seedAsset(882L, 1);

        assertThatThrownBy(() -> service.create(new CreateAudiobookGenerationRequest(883L, assetId,
                "audio-run-other-owner", AudiobookGenerationMode.FULL, true)))
                .isInstanceOf(AudiobookAssetNotFoundException.class);
        assertThatThrownBy(() -> service.create(new CreateAudiobookGenerationRequest(882L, assetId,
                "audio-run-no-rights", AudiobookGenerationMode.FULL, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCancelActiveGenerationAndExposeTheSchedulerTerminalState() {
        long ownerId = 8_830L;
        UUID assetId = seedAsset(ownerId, 1);
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "CANCELLED", List.of()));
        var created = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-cancel", AudiobookGenerationMode.FULL, true));

        var cancelled = service.cancel(created.id(), ownerId);

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.currentStage()).isEqualTo("CANCELLED");
        assertThat(cancelled.errorCode()).isNull();
        verify(schedulerClient).cancel(taskId);
    }

    @Test
    void shouldRetryOnlyTheLastFailedStageWithoutDiscardingFrozenContent() {
        long ownerId = 8_831L;
        UUID assetId = seedAsset(ownerId, 1);
        UUID failedTaskId = UUID.randomUUID();
        UUID retryTaskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(failedTaskId, retryTaskId);
        when(schedulerClient.getResults(failedTaskId)).thenReturn(new SchedulerResult(failedTaskId, "FAILED", List.of()));
        var created = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-retry", AudiobookGenerationMode.FULL, true));

        var failed = service.get(created.id(), ownerId);
        var retried = service.retry(created.id(), ownerId);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(retried.currentStage()).isEqualTo("TEXT_EXTRACTING");
        assertThat(retried.errorCode()).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter WHERE generation_id = ?
                """, Integer.class, created.id().toString())).isEqualTo(1);
        verify(schedulerClient, times(2)).createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap());
    }

    @Test
    void shouldNotRetryACharacterLimitRejection() {
        long ownerId = 8_832L;
        UUID assetId = seedAsset(ownerId, 1);
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(UUID.randomUUID());
        var created = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-limit-retry", AudiobookGenerationMode.FULL, true));
        service.saveTextProjectionBatch(created.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, hash('x'),
                        "storage://managed/audiobooks/large-retry.txt", 1_500_001L, 1_500_001L))));
        assertThatThrownBy(() -> service.completeTextProjection(created.id()))
                .isInstanceOf(AudiobookCharacterLimitExceededException.class);

        assertThatThrownBy(() -> service.retry(created.id(), ownerId))
                .isInstanceOf(IllegalStateException.class);
        verify(schedulerClient, times(1)).createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap());
    }

    @Test
    void shouldFreezeProjectedTextBeforeAdvancingTheGeneration() {
        long ownerId = 884L;
        UUID assetId = seedAsset(ownerId, 2);
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "QUEUED", List.of()));
        var created = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-projection", AudiobookGenerationMode.FULL, true));
        var input = service.textProjectionInput(created.id());

        assertThat(input.chapters()).hasSize(2);
        assertThat(input.chapters().getFirst().resourceRef()).isEqualTo("chapter-0");
        assertThatThrownBy(() -> service.completeTextProjection(created.id()))
                .isInstanceOf(IllegalStateException.class);

        service.saveTextProjectionBatch(created.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, hash('a'),
                        "storage://managed/audiobooks/one.txt", 11L),
                new AudiobookTextProjectionBatchRequest.Chapter(1, hash('b'),
                        "storage://managed/audiobooks/two.txt", 12L))));
        var completed = service.completeTextProjection(created.id());

        assertThat(completed.projectedChapterCount()).isEqualTo(2);
        assertThat(service.get(created.id(), ownerId).status()).isEqualTo("QUEUED");
        assertThat(service.get(created.id(), ownerId).currentStage()).isEqualTo("ANALYZING");
        assertThat(service.bookAnalysisInput(created.id()).chapters()).hasSize(2);
        service.saveBookAnalysis(created.id(), analysis("analysis-projection"));
        service.completeBookAnalysis(created.id());
        assertThat(service.get(created.id(), ownerId).currentStage()).isEqualTo("MATCHING_VOICES");
        service.saveVoicePlan(created.id(), voicePlan());
        service.completeVoicePlan(created.id());
        assertThat(service.get(created.id(), ownerId).currentStage()).isEqualTo("SYNTHESIZING");
        assertThatThrownBy(() -> service.saveTextProjectionBatch(created.id(),
                new AudiobookTextProjectionBatchRequest(List.of(
                        new AudiobookTextProjectionBatchRequest.Chapter(0, hash('c'),
                                "storage://managed/audiobooks/changed.txt", 11L)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectProjectedTextAboveConfiguredCharacterLimitBeforeBookAnalysis() {
        long ownerId = 8_841L;
        UUID assetId = seedAsset(ownerId, 1);
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(UUID.randomUUID());
        var created = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-character-limit", AudiobookGenerationMode.FULL, true));
        service.saveTextProjectionBatch(created.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, hash('a'),
                        "storage://managed/audiobooks/large.txt", 1_500_001L, 1_500_001L))));

        assertThatThrownBy(() -> service.completeTextProjection(created.id()))
                .isInstanceOf(AudiobookCharacterLimitExceededException.class);

        var rejected = service.get(created.id(), ownerId);
        assertThat(rejected.status()).isEqualTo("FAILED");
        assertThat(rejected.currentStage()).isEqualTo("FAILED");
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.AUDIOBOOK_CHARACTER_LIMIT_EXCEEDED.code());
        verify(schedulerClient, times(1)).createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap());
    }

    @Test
    void shouldRejectDailySynthesisQuotaWithoutChargingReusableOrRejectedGeneration() {
        long ownerId = 8_842L;
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(UUID.randomUUID());
        UUID firstAsset = seedAsset(ownerId, 1);
        UUID secondAsset = seedAsset(ownerId, 1);
        UUID rejectedAsset = seedAsset(ownerId, 1);
        var first = service.create(new CreateAudiobookGenerationRequest(ownerId, firstAsset,
                "audio-run-daily-quota-one", AudiobookGenerationMode.FULL, true));
        service.saveTextProjectionBatch(first.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, hash('a'),
                        "storage://managed/audiobooks/quota-one.txt", 1_500_000L, 1_500_000L))));
        service.completeTextProjection(first.id());
        var second = service.create(new CreateAudiobookGenerationRequest(ownerId, secondAsset,
                "audio-run-daily-quota-two", AudiobookGenerationMode.FULL, true));
        service.saveTextProjectionBatch(second.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, hash('b'),
                        "storage://managed/audiobooks/quota-two.txt", 1_500_000L, 1_500_000L))));
        service.completeTextProjection(second.id());
        var rejected = service.create(new CreateAudiobookGenerationRequest(ownerId, rejectedAsset,
                "audio-run-daily-quota-rejected", AudiobookGenerationMode.FULL, true));
        service.saveTextProjectionBatch(rejected.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, hash('c'),
                        "storage://managed/audiobooks/quota-rejected.txt", 1L, 1L))));

        assertThatThrownBy(() -> service.completeTextProjection(rejected.id()))
                .isInstanceOf(AudiobookDailyCharacterQuotaExceededException.class);

        assertThat(service.get(rejected.id(), ownerId).errorCode())
                .isEqualTo(ErrorCode.AUDIOBOOK_DAILY_CHARACTER_QUOTA_EXCEEDED.code());
        assertThatThrownBy(() -> service.retry(rejected.id(), ownerId))
                .isInstanceOf(AudiobookDailyCharacterQuotaExceededException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT reserved_character_count FROM audiobook_quota_usage
                WHERE owner_id = ? AND usage_date = CURRENT_DATE
                """, Long.class, ownerId)).isEqualTo(3_000_000L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_quota_reservation WHERE owner_id = ?
                """, Integer.class, ownerId)).isEqualTo(2);
    }

    @Test
    void shouldPersistReusableChapterAudioAfterSynthesis() {
        long ownerId = 885L;
        UUID assetId = seedAsset(ownerId, 1);
        UUID textTaskId = UUID.randomUUID();
        UUID synthesisTaskId = UUID.randomUUID();
        UUID analysisTaskId = UUID.randomUUID();
        UUID voiceTaskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(textTaskId, analysisTaskId, voiceTaskId, synthesisTaskId);
        when(schedulerClient.getResults(synthesisTaskId))
                .thenReturn(new SchedulerResult(synthesisTaskId, "QUEUED", List.of()));
        var created = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-synthesis", AudiobookGenerationMode.FULL, true));
        String sourceHash = hash('z');
        service.saveTextProjectionBatch(created.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, sourceHash,
                        "storage://managed/audiobooks/text.txt", 12L))));
        service.completeTextProjection(created.id());
        service.saveBookAnalysis(created.id(), analysis("analysis-synthesis"));
        service.completeBookAnalysis(created.id());
        service.saveVoicePlan(created.id(), voicePlan());
        service.completeVoicePlan(created.id());

        var synthesisInput = service.synthesisInput(created.id());
        assertThat(synthesisInput.narratorProvider()).isEqualTo("VOLCENGINE");
        assertThat(synthesisInput.narratorVoiceType()).isEqualTo("narrator");
        assertThat(synthesisInput.chapters()).singleElement()
                .satisfies(chapter -> assertThat(chapter.contentSha256()).isEqualTo(sourceHash));
        service.saveSynthesisBatch(created.id(), new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest(
                List.of(new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(0, sourceHash,
                        UUID.randomUUID(), "storage://managed/audiobooks/chapter.mp3", hash('m'), "mp3", 99L,
                        1234L))));
        var completed = service.completeSynthesis(created.id());

        assertThat(completed.synthesizedChapterCount()).isEqualTo(1);
        assertThat(service.get(created.id(), ownerId).status()).isEqualTo("COMPLETED");
        assertThat(service.playbackManifest(created.id(), ownerId).chapters()).singleElement()
                .satisfies(chapter -> {
                    assertThat(chapter.availability()).isEqualTo("READY");
                    assertThat(chapter.audioAssetId()).isNotNull();
                    assertThat(chapter.durationMs()).isEqualTo(1234L);
                });
        assertThat(service.playableChapter(created.id(), ownerId, 0))
                .satisfies(chapter -> {
                    assertThat(chapter.storageUri()).isEqualTo("storage://managed/audiobooks/chapter.mp3");
                    assertThat(chapter.sizeBytes()).isEqualTo(99L);
                    assertThat(chapter.format()).isEqualTo("mp3");
                });
        assertThatThrownBy(() -> service.playableChapter(created.id(), ownerId + 1, 0))
                .isInstanceOf(AudiobookGenerationNotFoundException.class);
        Integer audioCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_audio_asset WHERE generation_id = ?
                """, Integer.class, created.id().toString());
        assertThat(audioCount).isEqualTo(1);
    }

    @Test
    void shouldReuseUnchangedChapterAudioForIncrementalGeneration() {
        long ownerId = 886L;
        UUID assetId = seedAsset(ownerId, 2);
        UUID firstTextTask = UUID.randomUUID();
        UUID firstAnalysisTask = UUID.randomUUID();
        UUID firstVoiceTask = UUID.randomUUID();
        UUID firstSynthesisTask = UUID.randomUUID();
        UUID incrementalTextTask = UUID.randomUUID();
        UUID incrementalAnalysisTask = UUID.randomUUID();
        UUID incrementalVoiceTask = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(firstTextTask, firstAnalysisTask, firstVoiceTask, firstSynthesisTask,
                        incrementalTextTask, incrementalAnalysisTask, incrementalVoiceTask);
        var first = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-reuse-full", AudiobookGenerationMode.FULL, true));
        String firstHash = hash('a');
        String secondHash = hash('b');
        service.saveTextProjectionBatch(first.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, firstHash,
                        "storage://managed/audiobooks/reuse-one.txt", 11L),
                new AudiobookTextProjectionBatchRequest.Chapter(1, secondHash,
                        "storage://managed/audiobooks/reuse-two.txt", 12L))));
        service.completeTextProjection(first.id());
        service.saveBookAnalysis(first.id(), analysisWithLin("analysis-reuse-full"));
        service.completeBookAnalysis(first.id());
        service.saveVoicePlan(first.id(), voicePlanWithLin());
        service.completeVoicePlan(first.id());
        UUID firstAudio = UUID.randomUUID();
        UUID secondAudio = UUID.randomUUID();
        service.saveSynthesisBatch(first.id(), new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest(
                List.of(new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(0, firstHash,
                        firstAudio, "storage://managed/audiobooks/reuse-one.mp3", hash('t'), "mp3", 13L, 1400L),
                        new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(1, secondHash,
                                secondAudio, "storage://managed/audiobooks/reuse-two.mp3", hash('u'), "mp3", 14L,
                                1500L))));
        service.completeSynthesis(first.id());

        var incremental = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-reuse-incremental", AudiobookGenerationMode.INCREMENTAL, true));
        service.saveTextProjectionBatch(incremental.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, firstHash,
                        "storage://managed/audiobooks/reuse-one.txt", 11L),
                new AudiobookTextProjectionBatchRequest.Chapter(1, secondHash,
                        "storage://managed/audiobooks/reuse-two.txt", 12L))));
        service.completeTextProjection(incremental.id());
        var analysisInput = service.bookAnalysisInput(incremental.id());
        assertThat(analysisInput.chapters()).isEmpty();
        assertThat(analysisInput.inheritedAnalysis().characters())
                .extracting(character -> character.canonicalName()).containsExactly("Lin");
        assertThat(analysisInput.inheritedAnalysis().speechSegments()).singleElement()
                .satisfies(segment -> assertThat(segment.speakerCanonicalName()).isEqualTo("Lin"));
        service.saveBookAnalysis(incremental.id(), analysisWithLin("analysis-reuse-incremental"));
        service.completeBookAnalysis(incremental.id());
        assertThat(service.voiceMatchInput(incremental.id()).lockedBindings())
                .extracting(binding -> binding.roleKey()).containsExactly("CHARACTER:Lin", "NARRATOR");
        service.saveVoicePlan(incremental.id(), voicePlanWithLin());
        service.completeVoicePlan(incremental.id());

        assertThat(service.get(incremental.id(), ownerId).status()).isEqualTo("COMPLETED");
        assertThat(service.playbackManifest(incremental.id(), ownerId).chapters())
                .extracting(chapter -> chapter.audioAssetId()).containsExactly(firstAudio, secondAudio);
        Integer reused = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter
                WHERE generation_id = ? AND change_type = 'UNCHANGED' AND synthesis_status = 'READY'
                """, Integer.class, incremental.id().toString());
        assertThat(reused).isEqualTo(2);
    }

    @Test
    void shouldReuseUnchangedAudioAcrossManagedMediaAssetVersions() {
        long ownerId = 889L;
        UUID mediaItemId = UUID.randomUUID();
        UUID firstAssetId = seedManagedAsset(ownerId, 2, mediaItemId, hash('f'));
        UUID updatedAssetId = seedManagedAsset(ownerId, 3, mediaItemId, hash('g'));
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenAnswer(invocation -> UUID.randomUUID());

        var first = service.create(new CreateAudiobookGenerationRequest(ownerId, firstAssetId,
                "managed-audiobook-full", AudiobookGenerationMode.FULL, true));
        String firstHash = hash('a');
        String secondHash = hash('b');
        service.saveTextProjectionBatch(first.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, firstHash,
                        "storage://managed/audiobooks/managed-one.txt", 11L),
                new AudiobookTextProjectionBatchRequest.Chapter(1, secondHash,
                        "storage://managed/audiobooks/managed-two.txt", 12L))));
        service.completeTextProjection(first.id());
        service.saveBookAnalysis(first.id(), analysis("analysis-managed-full"));
        service.completeBookAnalysis(first.id());
        service.saveVoicePlan(first.id(), voicePlan());
        service.completeVoicePlan(first.id());
        UUID firstAudio = UUID.randomUUID();
        UUID secondAudio = UUID.randomUUID();
        service.saveSynthesisBatch(first.id(), new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest(
                List.of(new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(0, firstHash,
                        firstAudio, "storage://managed/audiobooks/managed-one.mp3", hash('m'), "mp3", 13L,
                        1400L),
                        new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(1, secondHash,
                                secondAudio, "storage://managed/audiobooks/managed-two.mp3", hash('n'), "mp3",
                                14L, 1500L))));
        service.completeSynthesis(first.id());

        var incremental = service.create(new CreateAudiobookGenerationRequest(ownerId, updatedAssetId,
                "managed-audiobook-incremental", AudiobookGenerationMode.INCREMENTAL, true));
        service.saveTextProjectionBatch(incremental.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, firstHash,
                        "storage://managed/audiobooks/managed-one.txt", 11L),
                new AudiobookTextProjectionBatchRequest.Chapter(1, secondHash,
                        "storage://managed/audiobooks/managed-two.txt", 12L),
                new AudiobookTextProjectionBatchRequest.Chapter(2, hash('c'),
                        "storage://managed/audiobooks/managed-three.txt", 13L))));
        service.completeTextProjection(incremental.id());

        var analysisInput = service.bookAnalysisInput(incremental.id());
        // 受管资产版本的章节身份变化时，即使正文可复用也不继承旧人物事实，避免跨版本污染。
        assertThat(analysisInput.chapters()).extracting(chapter -> chapter.index()).containsExactly(0, 1, 2);
        assertThat(analysisInput.inheritedAnalysis().characters()).isEmpty();

        assertThat(service.get(incremental.id(), ownerId).generationVersion()).isEqualTo(2);
        Integer reused = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter
                WHERE generation_id = ? AND change_type = 'MOVED_OR_RENUMBERED' AND synthesis_status = 'READY'
                """, Integer.class, incremental.id().toString());
        Integer appended = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audiobook_generation_chapter
                WHERE generation_id = ? AND change_type = 'APPENDED' AND synthesis_status = 'PENDING'
                """, Integer.class, incremental.id().toString());
        assertThat(reused).isEqualTo(2);
        assertThat(appended).isEqualTo(1);
    }

    @Test
    void shouldPersistAuditableCharactersAliasesRelationshipsAndSpeechSegments() {
        long ownerId = 887L;
        UUID assetId = seedAsset(ownerId, 2);
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(UUID.randomUUID(), UUID.randomUUID());
        var created = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-book-analysis", AudiobookGenerationMode.FULL, true));
        service.saveTextProjectionBatch(created.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, hash('a'),
                        "storage://managed/audiobooks/analysis-one.txt", 11L),
                new AudiobookTextProjectionBatchRequest.Chapter(1, hash('b'),
                        "storage://managed/audiobooks/analysis-two.txt", 12L))));
        service.completeTextProjection(created.id());

        var request = new AudiobookBookAnalysisBatchRequest(hash('c'), "test-analysis-model-v1",
                "BOOK_ANALYSIS_RULES_V2", List.of(
                new AudiobookBookAnalysisBatchRequest.Character("Lin", "Lin", "NEUTRAL", "HUMAN",
                        List.of("calm"), 0, 4, 0.91, List.of(
                        new AudiobookBookAnalysisBatchRequest.Alias("L", "SHORT_NAME", 0, 2, 3, 0.81))),
                new AudiobookBookAnalysisBatchRequest.Character("Chen", "Chen", "MASCULINE", "HUMAN",
                        List.of("direct"), 1, 2, 0.88, List.of())), List.of(
                new AudiobookBookAnalysisBatchRequest.Relationship("Lin", "Chen", "FRIEND", "OUTBOUND",
                        1, 0, 2, 0.77)), List.of(
                new AudiobookBookAnalysisBatchRequest.SpeechSegment(0, 0, 0, 4, "CHARACTER", "Lin",
                        List.of("calm"), 0.85, null)));

        var saved = service.saveBookAnalysis(created.id(), request);
        var repeated = service.saveBookAnalysis(created.id(), request);
        var view = service.bookAnalysis(created.id(), ownerId);

        assertThat(saved.characterCount()).isEqualTo(2);
        assertThat(saved.relationshipCount()).isEqualTo(1);
        assertThat(saved.speechSegmentCount()).isEqualTo(1);
        assertThat(repeated).isEqualTo(saved);
        assertThat(view.characters()).extracting(character -> character.canonicalName())
                .containsExactly("Lin", "Chen");
        assertThat(view.analysisModelVersion()).isEqualTo("test-analysis-model-v1");
        assertThat(view.analysisRuleVersion()).isEqualTo("BOOK_ANALYSIS_RULES_V2");
        assertThat(view.characters().getFirst().aliases()).singleElement()
                .satisfies(alias -> assertThat(alias.alias()).isEqualTo("L"));
        assertThat(view.relationships()).singleElement()
                .satisfies(relationship -> assertThat(relationship.relationshipType()).isEqualTo("FRIEND"));
        assertThat(view.speechSegments()).singleElement()
                .satisfies(segment -> assertThat(segment.speakerCanonicalName()).isEqualTo("Lin"));
        assertThatThrownBy(() -> service.saveBookAnalysis(created.id(), analysis("different-analysis")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.saveBookAnalysis(created.id(), new AudiobookBookAnalysisBatchRequest(
                request.analysisFingerprintSha256(), "test-analysis-model-v2", request.analysisRuleVersion(),
                request.characters(), request.relationships(), request.speechSegments())))
                .isInstanceOf(IllegalStateException.class);
        service.completeBookAnalysis(created.id());
        assertThat(service.voiceMatchInput(created.id()).characters())
                .extracting(character -> character.canonicalName()).containsExactly("Chen", "Lin");
        var voicePlan = voicePlanWithCharacters();
        assertThat(service.saveVoicePlan(created.id(), voicePlan).bindingCount()).isEqualTo(3);
        service.completeVoicePlan(created.id());
        assertThat(service.synthesisInput(created.id()).narratorVoiceType()).isEqualTo("narrator");
        assertThat(service.synthesisInput(created.id()).chapters()).hasSize(2);
        assertThat(service.synthesisInput(created.id()).chapters().getFirst().segments()).singleElement()
                .satisfies(segment -> {
                    assertThat(segment.voiceType()).isEqualTo("female");
                    assertThat(segment.confidence()).isEqualTo(0.85);
                });
    }

    @Test
    void shouldFreezeMultiCharacterQualityGateAttestationWithTheVoicePlan() {
        long ownerId = 8_871L;
        UUID assetId = seedAsset(ownerId, 1);
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(UUID.randomUUID(), UUID.randomUUID());
        var created = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-voice-quality-gate", AudiobookGenerationMode.FULL, true));
        service.saveTextProjectionBatch(created.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, hash('q'),
                        "storage://managed/audiobooks/quality-gate.txt", 12L))));
        service.completeTextProjection(created.id());
        service.saveBookAnalysis(created.id(), analysisWithLin("analysis-quality-gate"));
        service.completeBookAnalysis(created.id());
        AudiobookVoicePlanRequest original = voicePlanWithLin();
        AudiobookVoicePlanRequest attested = new AudiobookVoicePlanRequest(hash('q'), hash('g'), original.voices(),
                original.bindings());

        service.saveVoicePlan(created.id(), attested);

        assertThat(service.voicePlan(created.id(), ownerId).qualityGateAttestationSha256()).isEqualTo(hash('g'));
        assertThatThrownBy(() -> service.saveVoicePlan(created.id(), new AudiobookVoicePlanRequest(
                attested.voicePlanFingerprintSha256(), original.voices(), original.bindings())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCreateImmutableVoiceRevisionAndSynthesizeOnlyAffectedChapters() {
        long ownerId = 888L;
        UUID assetId = seedAsset(ownerId, 2);
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenAnswer(invocation -> UUID.randomUUID());
        var source = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-voice-revision-source", AudiobookGenerationMode.FULL, true));
        String firstSourceHash = hash('g');
        String secondSourceHash = hash('h');
        service.saveTextProjectionBatch(source.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, firstSourceHash,
                        "storage://managed/audiobooks/revision-one.txt", 11L),
                new AudiobookTextProjectionBatchRequest.Chapter(1, secondSourceHash,
                        "storage://managed/audiobooks/revision-two.txt", 12L))));
        service.completeTextProjection(source.id());
        service.saveBookAnalysis(source.id(), new AudiobookBookAnalysisBatchRequest(hash('i'),
                "test-analysis-model-v1", "BOOK_ANALYSIS_RULES_V2", List.of(
                new AudiobookBookAnalysisBatchRequest.Character("Lin", "Lin", "FEMININE", "HUMAN",
                        List.of("calm"), 0, 3, 0.92, List.of()),
                new AudiobookBookAnalysisBatchRequest.Character("Chen", "Chen", "MASCULINE", "HUMAN",
                        List.of("direct"), 1, 2, 0.86, List.of())), List.of(), List.of(
                new AudiobookBookAnalysisBatchRequest.SpeechSegment(0, 0, 0, 4, "CHARACTER", "Lin",
                        List.of("calm"), 0.65, "林说：“我来处理。”"))));
        service.completeBookAnalysis(source.id());
        service.saveVoicePlan(source.id(), attestedVoicePlanWithCharacters());
        service.completeVoicePlan(source.id());
        UUID sourceFirstAudio = UUID.randomUUID();
        UUID sourceSecondAudio = UUID.randomUUID();
        service.saveSynthesisBatch(source.id(), new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest(
                List.of(new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(0,
                        firstSourceHash, sourceFirstAudio, "storage://managed/audiobooks/revision-one.mp3",
                        hash('j'), "mp3", 20L, 1100L),
                        new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(1,
                                secondSourceHash, sourceSecondAudio,
                                "storage://managed/audiobooks/revision-two.mp3", hash('k'), "mp3", 21L,
                                1200L))));
        service.completeSynthesis(source.id());

        var request = new CreateAudiobookVoiceRevisionRequest("audio-run-voice-revision-child", "CHARACTER:Lin",
                "VOLCENGINE", "female-alt");
        var revision = service.createVoiceRevision(source.id(), ownerId, request);
        var duplicate = service.createVoiceRevision(source.id(), ownerId, request);

        assertThat(revision.id()).isEqualTo(duplicate.id());
        assertThat(revision.mode()).isEqualTo(AudiobookGenerationMode.REPAIR);
        assertThat(revision.generationVersion()).isEqualTo(2);
        assertThat(revision.currentStage()).isEqualTo("SYNTHESIZING");
        assertThat(service.voicePlan(source.id(), ownerId).bindings())
                .filteredOn(binding -> binding.roleKey().equals("CHARACTER:Lin"))
                .singleElement().satisfies(binding -> assertThat(binding.voiceType()).isEqualTo("female"));
        assertThat(service.voicePlan(revision.id(), ownerId).bindings())
                .filteredOn(binding -> binding.roleKey().equals("CHARACTER:Lin"))
                .singleElement().satisfies(binding -> {
                    assertThat(binding.voiceType()).isEqualTo("female-alt");
                    assertThat(binding.reviewStatus()).isEqualTo("MANUAL");
                });
        assertThat(service.voicePlan(revision.id(), ownerId).qualityGateAttestationSha256()).isEqualTo(hash('z'));
        assertThat(service.voiceCatalog(revision.id(), ownerId).voices())
                .extracting(voice -> voice.voiceType()).contains("narrator", "female", "female-alt", "male");
        assertThatThrownBy(() -> service.voiceCatalog(revision.id(), ownerId + 1))
                .isInstanceOf(AudiobookGenerationNotFoundException.class);
        assertThat(service.synthesisInput(revision.id()).chapters())
                .extracting(chapter -> chapter.index()).containsExactly(0);
        assertThat(service.playbackManifest(revision.id(), ownerId).chapters())
                .extracting(chapter -> chapter.availability()).containsExactly("PENDING", "READY");
        assertThat(service.playbackManifest(revision.id(), ownerId).chapters().get(1).audioAssetId())
                .isEqualTo(sourceSecondAudio);
        assertThat(service.playbackManifest(source.id(), ownerId).chapters().getFirst().audioAssetId())
                .isEqualTo(sourceFirstAudio);
        assertThatThrownBy(() -> service.createVoiceRevision(source.id(), ownerId + 1, request))
                .isInstanceOf(AudiobookGenerationNotFoundException.class);

        var speakerRequest = new CreateAudiobookSpeakerRevisionRequest("audio-run-speaker-revision-child", 0, 0,
                "CHARACTER", "Chen");
        var speakerRevision = service.createSpeakerRevision(source.id(), ownerId, speakerRequest);
        var duplicateSpeakerRevision = service.createSpeakerRevision(source.id(), ownerId, speakerRequest);

        assertThat(speakerRevision.id()).isEqualTo(duplicateSpeakerRevision.id());
        assertThat(speakerRevision.mode()).isEqualTo(AudiobookGenerationMode.REPAIR);
        assertThat(speakerRevision.generationVersion()).isEqualTo(3);
        assertThat(speakerRevision.currentStage()).isEqualTo("SYNTHESIZING");
        assertThat(service.synthesisInput(speakerRevision.id()).chapters())
                .extracting(chapter -> chapter.index()).containsExactly(0);
        assertThat(service.synthesisInput(speakerRevision.id()).chapters().getFirst().segments()).singleElement()
                .satisfies(segment -> assertThat(segment.voiceType()).isEqualTo("male"));
        assertThat(service.bookAnalysis(speakerRevision.id(), ownerId).analysisModelVersion())
                .isEqualTo("test-analysis-model-v1");
        assertThat(service.bookAnalysis(speakerRevision.id(), ownerId).analysisRuleVersion())
                .isEqualTo("BOOK_ANALYSIS_RULES_V2");
        assertThat(service.voicePlan(speakerRevision.id(), ownerId).qualityGateAttestationSha256())
                .isEqualTo(hash('z'));
        assertThat(service.bookAnalysis(speakerRevision.id(), ownerId).speechSegments()).singleElement()
                .satisfies(segment -> {
                    assertThat(segment.speakerCanonicalName()).isEqualTo("Chen");
                    assertThat(segment.confidence()).isEqualTo(1.0);
                    assertThat(segment.reviewStatus()).isEqualTo("MANUAL");
                    assertThat(segment.evidenceExcerpt()).isEqualTo("林说：“我来处理。”");
                });
        assertThat(service.bookAnalysis(source.id(), ownerId).speechSegments()).singleElement()
                .satisfies(segment -> {
                    assertThat(segment.speakerCanonicalName()).isEqualTo("Lin");
                    assertThat(segment.evidenceExcerpt()).isEqualTo("林说：“我来处理。”");
                });
        assertThat(service.playbackManifest(speakerRevision.id(), ownerId).chapters())
                .extracting(chapter -> chapter.availability()).containsExactly("PENDING", "READY");
        assertThat(service.playbackManifest(speakerRevision.id(), ownerId).chapters().get(1).audioAssetId())
                .isEqualTo(sourceSecondAudio);
        assertThatThrownBy(() -> service.createSpeakerRevision(source.id(), ownerId + 1, speakerRequest))
                .isInstanceOf(AudiobookGenerationNotFoundException.class);

        UUID revisionAudio = UUID.randomUUID();
        service.saveSynthesisBatch(revision.id(), new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest(
                List.of(new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(0,
                        firstSourceHash, revisionAudio, "storage://managed/audiobooks/revision-one-alt.mp3",
                        hash('l'), "mp3", 22L, 1300L))));
        service.completeSynthesis(revision.id());

        assertThat(service.get(revision.id(), ownerId).status()).isEqualTo("COMPLETED");
        assertThat(service.playbackManifest(revision.id(), ownerId).chapters().getFirst().audioAssetId())
                .isEqualTo(revisionAudio);
        assertThat(service.playbackManifest(source.id(), ownerId).chapters().getFirst().audioAssetId())
                .isEqualTo(sourceFirstAudio);
    }

    @Test
    void shouldCreatePronunciationRevisionAndRequeueOnlyExactMatchedChapters() {
        long ownerId = 889L;
        UUID assetId = seedAsset(ownerId, 2);
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenAnswer(invocation -> UUID.randomUUID());
        var source = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-pronunciation-revision-source", AudiobookGenerationMode.FULL, true));
        String firstSourceHash = hash('m');
        String secondSourceHash = hash('n');
        service.saveTextProjectionBatch(source.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, firstSourceHash,
                        "storage://managed/audiobooks/pronunciation-one.txt", 11L),
                new AudiobookTextProjectionBatchRequest.Chapter(1, secondSourceHash,
                        "storage://managed/audiobooks/pronunciation-two.txt", 12L))));
        service.completeTextProjection(source.id());
        service.saveBookAnalysis(source.id(), analysis("analysis-pronunciation"));
        service.completeBookAnalysis(source.id());
        service.saveVoicePlan(source.id(), attestedVoicePlan());
        service.completeVoicePlan(source.id());
        UUID sourceFirstAudio = UUID.randomUUID();
        UUID sourceSecondAudio = UUID.randomUUID();
        service.saveSynthesisBatch(source.id(), new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest(
                List.of(new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(0,
                        firstSourceHash, sourceFirstAudio,
                        "storage://managed/audiobooks/pronunciation-one.mp3", hash('o'), "mp3", 20L, 1100L),
                        new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(1,
                                secondSourceHash, sourceSecondAudio,
                                "storage://managed/audiobooks/pronunciation-two.mp3", hash('p'), "mp3", 21L,
                                1200L))));
        service.completeSynthesis(source.id());

        var request = new CreateAudiobookPronunciationRevisionRequest(
                "audio-run-pronunciation-revision-child", "银行", "yin2 hang2");
        var revision = service.createPronunciationRevision(source.id(), ownerId, request);
        var duplicate = service.createPronunciationRevision(source.id(), ownerId, request);

        assertThat(revision.id()).isEqualTo(duplicate.id());
        assertThat(revision.mode()).isEqualTo(AudiobookGenerationMode.REPAIR);
        assertThat(revision.generationVersion()).isEqualTo(2);
        assertThat(revision.currentStage()).isEqualTo("PREPARING_PRONUNCIATION");
        assertThat(service.voicePlan(revision.id(), ownerId).qualityGateAttestationSha256()).isEqualTo(hash('z'));
        assertThat(service.pronunciationDictionary(revision.id(), ownerId).entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.term()).isEqualTo("银行");
                    assertThat(entry.pinyin()).isEqualTo("yin2 hang2");
                });
        assertThat(service.pronunciationPreparationInput(revision.id()).chapters())
                .extracting(chapter -> chapter.index()).containsExactly(0, 1);
        assertThat(service.playbackManifest(revision.id(), ownerId).chapters())
                .extracting(chapter -> chapter.availability()).containsExactly("READY", "READY");

        var prepared = service.completePronunciationPreparation(revision.id(), List.of(1));

        assertThat(prepared.affectedChapterCount()).isEqualTo(1);
        assertThat(service.get(revision.id(), ownerId).currentStage()).isEqualTo("SYNTHESIZING");
        assertThat(service.synthesisInput(revision.id()).pronunciations()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.term()).isEqualTo("银行");
                    assertThat(entry.pinyin()).isEqualTo("yin2 hang2");
                });
        assertThat(service.synthesisInput(revision.id()).chapters())
                .extracting(chapter -> chapter.index()).containsExactly(1);
        assertThat(service.playbackManifest(revision.id(), ownerId).chapters())
                .extracting(chapter -> chapter.availability()).containsExactly("READY", "PENDING");
        assertThat(service.playbackManifest(revision.id(), ownerId).chapters().getFirst().audioAssetId())
                .isEqualTo(sourceFirstAudio);
        assertThat(service.playbackManifest(source.id(), ownerId).chapters().get(1).audioAssetId())
                .isEqualTo(sourceSecondAudio);
        assertThatThrownBy(() -> service.createPronunciationRevision(source.id(), ownerId + 1, request))
                .isInstanceOf(AudiobookGenerationNotFoundException.class);
    }

    @Test
    void shouldCreateImmutableZipExportOnlyForCompletedGeneration() {
        long ownerId = 890L;
        UUID assetId = seedAsset(ownerId, 1);
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenAnswer(invocation -> UUID.randomUUID());
        var generation = service.create(new CreateAudiobookGenerationRequest(ownerId, assetId,
                "audio-run-export-source", AudiobookGenerationMode.FULL, true));
        String sourceHash = hash('z');
        service.saveTextProjectionBatch(generation.id(), new AudiobookTextProjectionBatchRequest(List.of(
                new AudiobookTextProjectionBatchRequest.Chapter(0, sourceHash,
                        "storage://managed/audiobooks/export-source.txt", 12L))));
        service.completeTextProjection(generation.id());
        service.saveBookAnalysis(generation.id(), analysis("analysis-export"));
        service.completeBookAnalysis(generation.id());
        service.saveVoicePlan(generation.id(), voicePlan());
        service.completeVoicePlan(generation.id());
        service.saveSynthesisBatch(generation.id(), new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest(
                List.of(new com.yuyutian.mytools.reader.model.AudiobookSynthesisBatchRequest.Chapter(0, sourceHash,
                        UUID.randomUUID(), "storage://managed/audiobooks/export-source.mp3", hash('x'), "mp3",
                        31L, 1500L))));
        service.completeSynthesis(generation.id());

        var request = new CreateAudiobookExportRequest("audiobook-export-zip", AudiobookExportFormat.ZIP);
        var export = service.createExport(generation.id(), ownerId, request);
        var duplicate = service.createExport(generation.id(), ownerId, request);

        assertThat(duplicate.id()).isEqualTo(export.id());
        assertThat(export.status()).isEqualTo("QUEUED");
        assertThat(export.currentStage()).isEqualTo("PACKAGING");
        assertThat(service.exportInput(export.id()).chapters()).singleElement()
                .satisfies(chapter -> assertThat(chapter.storageUri())
                        .isEqualTo("storage://managed/audiobooks/export-source.mp3"));
        var completed = service.completeExport(export.id(), new AudiobookExportResult(
                "storage://managed/audiobooks/export.zip", hash('q'), 321L, 1));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.sizeBytes()).isEqualTo(321L);
        assertThat(service.getExport(generation.id(), export.id(), ownerId).status()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> service.getExport(generation.id(), export.id(), ownerId + 1))
                .isInstanceOf(AudiobookExportNotFoundException.class);
        assertThatThrownBy(() -> service.exportInput(export.id()))
                .isInstanceOf(AudiobookExportNotFoundException.class);
    }

    private UUID seedAsset(long ownerId, int chapters) {
        return seedAsset(ownerId, chapters, "{}", hash('f'));
    }

    private UUID seedManagedAsset(long ownerId, int chapters, UUID mediaItemId, String contentSha256) {
        return seedAsset(ownerId, chapters, "{\"managedMediaItemId\":\"" + mediaItemId + "\"}", contentSha256);
    }

    private UUID seedAsset(long ownerId, int chapters, String metadataJson, String contentSha256) {
        Instant now = Instant.now();
        UUID sourceId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String sourceKey = "source-" + ownerId + "-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO book_source (id, owner_id, sync_key, name, source_url, enabled, current_version,
                                         created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE, 1, ?, ?)
                """, sourceId.toString(), ownerId, sourceKey, "Audiobook Test Source",
                "https://example.test/source", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO ebook_import_request
                    (id, owner_id, idempotency_key, source_id, source_version, book_url, requested_title,
                     requested_author, storage_root, status, parameters_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, 'SUCCEEDED', '{}', ?, ?)
                """, importId.toString(), ownerId, "import-" + UUID.randomUUID(), sourceId.toString(),
                "https://example.test/book", "Audiobook Test", "Author", "managed",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO ebook_asset
                    (id, import_request_id, owner_id, source_id, title, author, format, storage_uri, size_bytes,
                     content_sha256, chapter_count, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'TXT', ?, 100, ?, ?, ?, ?, ?)
                """, assetId.toString(), importId.toString(), ownerId, sourceId.toString(), "Audiobook Test",
                "Author", "storage://managed/test.txt", contentSha256, chapters, metadataJson,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        for (int index = 0; index < chapters; index++) {
            jdbcTemplate.update("""
                    INSERT INTO ebook_catalog_entry
                        (import_request_id, entry_index, title, resource_ref, start_offset, end_offset,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, importId.toString(), index, "Chapter " + index, "chapter-" + index,
                    (long) index * 100, (long) (index + 1) * 100,
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        }
        return assetId;
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private AudiobookBookAnalysisBatchRequest analysis(String value) {
        return new AudiobookBookAnalysisBatchRequest(hash(value.charAt(0)), "test-analysis-model-v1",
                "BOOK_ANALYSIS_RULES_V2", List.of(), List.of(), List.of());
    }

    private AudiobookBookAnalysisBatchRequest analysisWithLin(String value) {
        return new AudiobookBookAnalysisBatchRequest(hash(value.charAt(0)), "test-analysis-model-v1",
                "BOOK_ANALYSIS_RULES_V2", List.of(
                new AudiobookBookAnalysisBatchRequest.Character("Lin", "Lin", "FEMININE", "HUMAN",
                        List.of("calm"), 0, 2, 0.91, List.of(
                        new AudiobookBookAnalysisBatchRequest.Alias("L", "SHORT_NAME", 0, 0, 1, 0.82)))),
                List.of(), List.of(
                new AudiobookBookAnalysisBatchRequest.SpeechSegment(0, 0, 0, 1, "CHARACTER", "Lin",
                        List.of("calm"), 0.91, null)));
    }

    private AudiobookVoicePlanRequest voicePlan() {
        return new AudiobookVoicePlanRequest(hash('v'), List.of(
                new AudiobookVoicePlanRequest.Voice("VOLCENGINE", "narrator", "zh-CN", "NEUTRAL", "ADULT",
                        List.of("calm"), true, "v1", true, null, null, null, null, null)), List.of(
                new AudiobookVoicePlanRequest.Binding("NARRATOR", null, "VOLCENGINE", "narrator", 1.0,
                        "RULES_V1", List.of("configured_catalog"), true)));
    }

    private AudiobookVoicePlanRequest attestedVoicePlan() {
        AudiobookVoicePlanRequest original = voicePlan();
        return new AudiobookVoicePlanRequest(original.voicePlanFingerprintSha256(), hash('z'), original.voices(),
                original.bindings());
    }

    private AudiobookVoicePlanRequest voicePlanWithLin() {
        return new AudiobookVoicePlanRequest(hash('x'), List.of(
                new AudiobookVoicePlanRequest.Voice("VOLCENGINE", "narrator", "zh-CN", "NEUTRAL", "ADULT",
                        List.of("calm"), true, "v1", true, null, null, null, null, null),
                new AudiobookVoicePlanRequest.Voice("VOLCENGINE", "female", "zh-CN", "FEMININE", "ADULT",
                        List.of("calm"), false, "v1", true, null, null, null, null, null)), List.of(
                new AudiobookVoicePlanRequest.Binding("NARRATOR", null, "VOLCENGINE", "narrator", 1.0,
                        "RULES_V1", List.of("configured_catalog"), true),
                new AudiobookVoicePlanRequest.Binding("CHARACTER:Lin", "Lin", "VOLCENGINE", "female", 0.9,
                        "RULES_V1", List.of("presentation_exact"), true)));
    }

    private AudiobookVoicePlanRequest voicePlanWithCharacters() {
        return new AudiobookVoicePlanRequest(hash('w'), List.of(
                new AudiobookVoicePlanRequest.Voice("VOLCENGINE", "narrator", "zh-CN", "NEUTRAL", "ADULT",
                        List.of("calm"), true, "v1", true, null, null, null, null, null),
                new AudiobookVoicePlanRequest.Voice("VOLCENGINE", "female", "zh-CN", "FEMININE", "ADULT",
                        List.of("warm"), false, "v1", true, null, null, null, null, null),
                new AudiobookVoicePlanRequest.Voice("VOLCENGINE", "female-alt", "zh-CN", "FEMININE", "ADULT",
                        List.of("warm"), false, "v1", true, null, null, null, null, null),
                new AudiobookVoicePlanRequest.Voice("VOLCENGINE", "male", "zh-CN", "MASCULINE", "ADULT",
                        List.of("direct"), false, "v1", true, null, null, null, null, null)), List.of(
                new AudiobookVoicePlanRequest.Binding("NARRATOR", null, "VOLCENGINE", "narrator", 1.0,
                        "RULES_V1", List.of("configured_catalog"), true),
                new AudiobookVoicePlanRequest.Binding("CHARACTER:Lin", "Lin", "VOLCENGINE", "female", 0.9,
                        "RULES_V1", List.of("presentation_exact"), true),
                new AudiobookVoicePlanRequest.Binding("CHARACTER:Chen", "Chen", "VOLCENGINE", "male", 0.9,
                        "RULES_V1", List.of("presentation_exact"), true)));
    }

    private AudiobookVoicePlanRequest attestedVoicePlanWithCharacters() {
        AudiobookVoicePlanRequest original = voicePlanWithCharacters();
        return new AudiobookVoicePlanRequest(original.voicePlanFingerprintSha256(), hash('z'), original.voices(),
                original.bindings());
    }
}
