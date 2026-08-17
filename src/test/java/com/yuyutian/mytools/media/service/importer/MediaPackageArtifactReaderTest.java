package com.yuyutian.mytools.media.service.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.media.model.MediaPackageManifest;
import com.yuyutian.mytools.media.model.MediaTagArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaPackageArtifactReaderTest {

    private static final String CONTENT_SHA256 = "a".repeat(64);
    private static final String INPUT_SHA256 = "b".repeat(64);
    private final MediaPackageArtifactReader reader = new MediaPackageArtifactReader(new ObjectMapper());

    @TempDir
    Path tempDirectory;

    @Test
    void readsCompatibleDownloadBotArtifacts() throws Exception {
        Files.writeString(tempDirectory.resolve(".ready"), "ready\n");
        Files.write(tempDirectory.resolve("original-video.mp4"), new byte[] { 1, 2, 3, 4 });
        Files.writeString(tempDirectory.resolve("metadata.json"), manifestJson("original-video.mp4", CONTENT_SHA256));
        Files.writeString(tempDirectory.resolve("tags.json"), tagsJson(CONTENT_SHA256));

        MediaPackageManifest manifest = reader.readManifest(tempDirectory);
        MediaTagArtifact artifact = reader.readTagArtifact(tempDirectory, manifest);

        assertEquals("package-123", manifest.packageId());
        assertEquals("海边日落", artifact.tags().getFirst().name());
    }

    @Test
    void rejectsVideoPathTraversal() throws Exception {
        Files.writeString(tempDirectory.resolve(".ready"), "ready\n");
        Files.writeString(tempDirectory.resolve("metadata.json"), manifestJson("../outside.mp4", CONTENT_SHA256));

        assertThrows(MediaPackageArtifactException.class, () -> reader.readManifest(tempDirectory));
    }

    @Test
    void rejectsTagArtifactForDifferentContent() throws Exception {
        Files.writeString(tempDirectory.resolve(".ready"), "ready\n");
        Files.write(tempDirectory.resolve("original-video.mp4"), new byte[] { 1, 2, 3, 4 });
        Files.writeString(tempDirectory.resolve("metadata.json"), manifestJson("original-video.mp4", CONTENT_SHA256));
        Files.writeString(tempDirectory.resolve("tags.json"), tagsJson("c".repeat(64)));

        MediaPackageManifest manifest = reader.readManifest(tempDirectory);
        assertThrows(MediaPackageArtifactException.class,
                () -> reader.readTagArtifact(tempDirectory, manifest));
    }

    private String manifestJson(String videoFile, String sha256) {
        return """
                {
                  "schemaVersion": 1,
                  "packageId": "package-123",
                  "packageStatus": "READY",
                  "sourceType": "DOWNLOAD_BOT",
                  "sourceAssetId": 123,
                  "sourceEventKey": "telegram:tg-main:123456",
                  "originalFileName": "original.mp4",
                  "videoFile": "%s",
                  "contentSha256": "%s",
                  "sizeBytes": 4,
                  "mimeType": "video/mp4",
                  "storagePolicyVersion": "big-video-v1",
                  "tagStatus": "READY",
                  "tagArtifact": "tags.json",
                  "createdAt": "2026-08-15T21:30:45+08:00",
                  "updatedAt": "2026-08-15T21:38:10+08:00"
                }
                """.formatted(videoFile, sha256);
    }

    private String tagsJson(String sha256) {
        return """
                {
                  "schemaVersion": 1,
                  "status": "READY",
                  "contentSha256": "%s",
                  "producer": "DOWNLOAD_BOT",
                  "provider": "ollama",
                  "model": "huihui_ai/qwen3-vl-abliterated:4b",
                  "promptVersion": "media-tags-v1",
                  "inputKind": "VIDEO_THUMBNAIL",
                  "inputFingerprint": "%s",
                  "generatedAt": "2026-08-15T21:38:10+08:00",
                  "tags": [
                    { "name": "海边日落", "type": "topic", "confidence": 0.94 }
                  ]
                }
                """.formatted(sha256, INPUT_SHA256);
    }
}
