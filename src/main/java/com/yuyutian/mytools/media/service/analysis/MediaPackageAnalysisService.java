package com.yuyutian.mytools.media.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.media.model.MediaPackageManifest;
import com.yuyutian.mytools.media.model.MediaTagArtifact;
import com.yuyutian.mytools.media.model.VideoDescription;
import com.yuyutian.mytools.media.model.VideoMetadata;
import com.yuyutian.mytools.media.service.importer.MediaPackageArtifactReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 编排大视频资源包的元数据、截图和文字介绍生成流程。
 */
@Service
@RequiredArgsConstructor
public class MediaPackageAnalysisService {

    private static final String PIPELINE_VERSION = "mytools-media-v1";
    private static final long TAGGING_GRACE_HOURS = 24L;

    private final ObjectMapper objectMapper;
    private final MediaPackageArtifactReader artifactReader;
    private final VideoProbeService probeService;
    private final VideoStoryboardService storyboardService;
    private final VideoDescriptionService descriptionService;
    private final MediaPackageFileWriter fileWriter;

    /**
     * 判断资源包是否需要首次执行或到期重试。
     *
     * @param packageDirectory 资源包目录
     * @return 是否需要执行
     */
    public boolean needsAnalysis(Path packageDirectory) {
        try {
            JsonNode metadata = objectMapper.readTree(packageDirectory.resolve("metadata.json").toFile());
            String tagStatus = metadata.path("tagStatus").asText("");
            if (("PENDING".equals(tagStatus) || "RUNNING".equals(tagStatus))
                    && !isTaggingStale(metadata)) {
                // 等待 DownloadBot 完成其唯一标签任务，避免双方并发改写清单。
                return false;
            }
            String analysisStatus = metadata.path("analysisStatus").asText("");
            if ("READY".equals(analysisStatus) || "UNRECOVERABLE".equals(analysisStatus)) {
                return false;
            }
            String retryAfter = metadata.path("retryAfter").asText("");
            return retryAfter.isBlank() || !OffsetDateTime.parse(retryAfter).isAfter(now());
        } catch (Exception ex) {
            return true;
        }
    }

    private boolean isTaggingStale(JsonNode metadata) {
        String updatedAt = metadata.path("updatedAt").asText("");
        if (updatedAt.isBlank()) {
            updatedAt = metadata.path("createdAt").asText("");
        }
        if (updatedAt.isBlank()) {
            return false;
        }
        try {
            OffsetDateTime deadline = OffsetDateTime.parse(updatedAt).plus(TAGGING_GRACE_HOURS, ChronoUnit.HOURS);
            return !deadline.isAfter(now());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 完整分析一个已发布资源包，并原子更新所有伴生文件。
     *
     * @param packageDirectory 资源包目录
     * @throws IOException 分析或产物写入失败
     */
    public void analyze(Path packageDirectory) throws IOException {
        Path metadataPath = packageDirectory.resolve("metadata.json");
        ObjectNode metadata = readMetadata(metadataPath);
        int attempts = metadata.path("analysisAttempts").asInt(0) + 1;
        updateStatus(metadataPath, metadata, "RUNNING", attempts, null, null);
        try {
            MediaPackageManifest manifest = artifactReader.readManifest(packageDirectory);
            Path video = packageDirectory.resolve(manifest.videoFile());
            VideoMetadata videoMetadata = probeService.probe(video);
            List<Path> screenshots = storyboardService.generate(
                    video, packageDirectory, videoMetadata.durationMs());
            VideoDescription description = descriptionService.generate(
                    manifest.originalFileName(), videoMetadata, readTags(packageDirectory, manifest), screenshots);

            fileWriter.writeText(packageDirectory.resolve("summary.txt"), description.summary() + System.lineSeparator());
            fileWriter.writeText(packageDirectory.resolve("description.md"), description.description() + System.lineSeparator());
            applyVideoMetadata(metadata, videoMetadata, screenshots);
            metadata.put("summary", description.summary());
            metadata.put("descriptionFile", "description.md");
            metadata.put("thumbnailFile", "thumbnail.jpg");
            metadata.put("analysisPipelineVersion", PIPELINE_VERSION);
            updateStatus(metadataPath, metadata, "READY", attempts, null, null);
        } catch (Exception ex) {
            long delayMinutes = Math.min(360L, 5L << Math.min(attempts - 1, 6));
            updateStatus(metadataPath, metadata, "FAILED", attempts,
                    ex.getClass().getSimpleName(), now().plusMinutes(delayMinutes).toString());
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Media package analysis failed", ex);
        }
    }

    private ObjectNode readMetadata(Path metadataPath) throws IOException {
        JsonNode node = objectMapper.readTree(metadataPath.toFile());
        if (!(node instanceof ObjectNode objectNode)) {
            throw new IOException("Media package metadata must be a JSON object");
        }
        return objectNode;
    }

    private List<String> readTags(Path packageDirectory, MediaPackageManifest manifest) {
        if (!"READY".equals(manifest.tagStatus())) {
            return List.of();
        }
        try {
            MediaTagArtifact artifact = artifactReader.readTagArtifact(packageDirectory, manifest);
            return artifact.tags().stream().map(MediaTagArtifact.Tag::name).toList();
        } catch (RuntimeException ex) {
            // 标签不是视频分析的硬依赖，损坏时由现有标签导入流程接管。
            return List.of();
        }
    }

    private void applyVideoMetadata(ObjectNode target, VideoMetadata video, List<Path> screenshots) {
        ObjectNode details = target.putObject("videoMetadata");
        details.put("durationMs", video.durationMs());
        details.put("format", video.format());
        details.put("videoCodec", video.videoCodec());
        details.put("audioCodec", video.audioCodec());
        details.put("width", video.width());
        details.put("height", video.height());
        details.put("frameRate", video.frameRate());
        details.put("bitRate", video.bitRate());
        ArrayNode frames = target.putArray("storyboardFiles");
        screenshots.stream().map(Path::getFileName).map(Path::toString)
                // 清单统一记录资源包内的相对路径，避免读取端丢失 storyboard 目录。
                .map(fileName -> "storyboard/" + fileName).forEach(frames::add);
    }

    private void updateStatus(Path metadataPath, ObjectNode metadata, String status, int attempts,
                              String errorCode, String retryAfter) throws IOException {
        ObjectNode latest = readMetadata(metadataPath);
        copyAnalysisField(metadata, latest, "videoMetadata");
        copyAnalysisField(metadata, latest, "storyboardFiles");
        copyAnalysisField(metadata, latest, "summary");
        copyAnalysisField(metadata, latest, "descriptionFile");
        copyAnalysisField(metadata, latest, "thumbnailFile");
        copyAnalysisField(metadata, latest, "analysisPipelineVersion");
        latest.put("analysisStatus", status);
        latest.put("analysisAttempts", attempts);
        latest.put("analysisUpdatedAt", now().toString());
        if (errorCode == null) {
            latest.remove("analysisErrorCode");
        } else {
            latest.put("analysisErrorCode", errorCode);
        }
        if (retryAfter == null) {
            latest.remove("retryAfter");
        } else {
            latest.put("retryAfter", retryAfter);
        }
        fileWriter.writeJson(metadataPath, latest);
    }

    private void copyAnalysisField(ObjectNode source, ObjectNode target, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field));
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
