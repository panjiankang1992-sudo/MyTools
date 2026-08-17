package com.yuyutian.mytools.media.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.media.model.MediaPackageManifest;
import com.yuyutian.mytools.media.model.VideoDescription;
import com.yuyutian.mytools.media.model.VideoMetadata;
import com.yuyutian.mytools.media.service.importer.MediaPackageArtifactReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaPackageAnalysisServiceTest {

    @TempDir
    Path packageDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MediaPackageArtifactReader artifactReader;
    private VideoProbeService probeService;
    private VideoStoryboardService storyboardService;
    private VideoDescriptionService descriptionService;
    private MediaPackageAnalysisService service;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(packageDirectory.resolve("metadata.json"), """
                {"schemaVersion":1,"tagStatus":"FAILED"}
                """);
        Files.write(packageDirectory.resolve("video.mp4"), new byte[]{1, 2, 3});
        artifactReader = mock(MediaPackageArtifactReader.class);
        probeService = mock(VideoProbeService.class);
        storyboardService = mock(VideoStoryboardService.class);
        descriptionService = mock(VideoDescriptionService.class);
        service = new MediaPackageAnalysisService(objectMapper, artifactReader, probeService,
                storyboardService, descriptionService, new MediaPackageFileWriter(objectMapper));
        when(artifactReader.readManifest(packageDirectory)).thenReturn(manifest());
    }

    @Test
    void shouldPersistReadyArtifactsAndSkipCompletedPackage() throws Exception {
        VideoMetadata videoMetadata = new VideoMetadata(
                120_000L, "mp4", "h264", "aac", 1920, 1080, 25D, 2_000_000L);
        List<Path> frames = List.of(packageDirectory.resolve("storyboard/01.jpg"),
                packageDirectory.resolve("storyboard/02.jpg"));
        when(probeService.probe(any())).thenReturn(videoMetadata);
        when(storyboardService.generate(any(), any(), any(Long.class))).thenReturn(frames);
        when(descriptionService.generate(any(), any(), any(), any()))
                .thenReturn(new VideoDescription("简短摘要", "详细介绍"));

        service.analyze(packageDirectory);

        JsonNode metadata = objectMapper.readTree(packageDirectory.resolve("metadata.json").toFile());
        assertThat(metadata.path("analysisStatus").asText()).isEqualTo("READY");
        assertThat(metadata.path("analysisAttempts").asInt()).isEqualTo(1);
        assertThat(metadata.path("videoMetadata").path("durationMs").asLong()).isEqualTo(120_000L);
        assertThat(metadata.path("storyboardFiles")).hasSize(2);
        assertThat(metadata.path("storyboardFiles").get(0).asText()).isEqualTo("storyboard/01.jpg");
        assertThat(Files.readString(packageDirectory.resolve("summary.txt"))).contains("简短摘要");
        assertThat(Files.readString(packageDirectory.resolve("description.md"))).contains("详细介绍");
        assertThat(service.needsAnalysis(packageDirectory)).isFalse();
    }

    @Test
    void shouldPersistFailureAndRetryTime() throws Exception {
        when(probeService.probe(any())).thenThrow(new IOException("invalid video"));

        assertThatThrownBy(() -> service.analyze(packageDirectory)).isInstanceOf(IOException.class);

        JsonNode metadata = objectMapper.readTree(packageDirectory.resolve("metadata.json").toFile());
        assertThat(metadata.path("analysisStatus").asText()).isEqualTo("FAILED");
        assertThat(metadata.path("analysisErrorCode").asText()).isEqualTo("IOException");
        assertThat(metadata.path("retryAfter").asText()).isNotBlank();
        assertThat(service.needsAnalysis(packageDirectory)).isFalse();
    }

    @Test
    void shouldWaitWhileDownloadBotTaggingIsActive() throws Exception {
        Files.writeString(packageDirectory.resolve("metadata.json"), """
                {"schemaVersion":1,"tagStatus":"RUNNING"}
                """);

        assertThat(service.needsAnalysis(packageDirectory)).isFalse();
    }

    private MediaPackageManifest manifest() {
        return new MediaPackageManifest(1, "package-1", "READY", "DOWNLOAD_BOT", 1L,
                "event-1", "original.mp4", "video.mp4", "a".repeat(64), 3L, "video/mp4",
                "v1", "PENDING", "tags.json", "2026-08-15T00:00:00Z", "2026-08-15T00:00:00Z");
    }
}
