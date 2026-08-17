package com.yuyutian.mytools.media.service.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.media.mapper.MediaTagArtifactAuditMapper;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MediaPackageTagImportServiceTest {

    private static final String CONTENT_SHA256 = "a".repeat(64);

    @TempDir
    Path tempDirectory;

    @Test
    void importsReadyDownloadBotTagsWithoutLocalInference() throws Exception {
        Path video = preparePackage("READY", true);
        LocalFileMapper fileMapper = mock(LocalFileMapper.class);
        FileTagMapper tagMapper = mock(FileTagMapper.class);
        MediaTagArtifactAuditMapper auditMapper = mock(MediaTagArtifactAuditMapper.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        MediaPackageTagImportService service = new MediaPackageTagImportService(
                new MediaPackageArtifactReader(new ObjectMapper()), fileMapper, tagMapper,
                auditMapper, idGenerator);
        LocalFile file = localFile(video);

        assertTrue(service.reconcile(file));

        verify(tagMapper).deleteByFileId(7L);
        verify(tagMapper).batchInsert(argThat(tags -> tags.size() == 1
                && "海边日落".equals(tags.getFirst().getTagName())));
        verify(auditMapper).upsert(any(Long.class), eq(7L), eq(CONTENT_SHA256),
                eq("DOWNLOAD_BOT"), eq("ollama"), eq("test-model"), eq("media-tags-v1"),
                eq("VIDEO_THUMBNAIL"), eq("b".repeat(64)), eq("READY"), any(), any());
        verify(fileMapper).updateTaggingStatus(eq(7L), eq(MediaPackageTagImportService.TAG_READY), any());
        assertEquals(MediaPackageTagImportService.TAG_READY, file.getTaggingStatus());
    }

    @Test
    void externalPendingPackageIsExcludedFromLocalTagQueue() throws Exception {
        Path video = preparePackage("PENDING", false);
        LocalFileMapper fileMapper = mock(LocalFileMapper.class);
        FileTagMapper tagMapper = mock(FileTagMapper.class);
        MediaPackageTagImportService service = new MediaPackageTagImportService(
                new MediaPackageArtifactReader(new ObjectMapper()), fileMapper, tagMapper,
                mock(MediaTagArtifactAuditMapper.class), mock(SnowflakeIdGenerator.class));
        LocalFile file = localFile(video);

        assertTrue(service.reconcile(file));

        verify(fileMapper).updateTaggingStatus(
                eq(7L), eq(MediaPackageTagImportService.TAG_EXTERNAL_PENDING), any());
        verify(tagMapper, never()).batchInsert(any());
        assertEquals(MediaPackageTagImportService.TAG_EXTERNAL_PENDING, file.getTaggingStatus());
    }

    @Test
    void ignoresPackageCompanionImage() throws Exception {
        Path video = preparePackage("READY", true);
        LocalFileMapper fileMapper = mock(LocalFileMapper.class);
        FileTagMapper tagMapper = mock(FileTagMapper.class);
        MediaPackageTagImportService service = new MediaPackageTagImportService(
                new MediaPackageArtifactReader(new ObjectMapper()), fileMapper, tagMapper,
                mock(MediaTagArtifactAuditMapper.class), mock(SnowflakeIdGenerator.class));
        LocalFile image = localFile(video.resolveSibling("thumbnail.jpg"));
        image.setMimeType("image/jpeg");

        assertFalse(service.reconcile(image));
        verify(fileMapper, never()).updateTaggingStatus(any(), any(), any());
    }

    private Path preparePackage(String status, boolean readyTags) throws Exception {
        Path video = tempDirectory.resolve("video.mp4");
        Files.write(video, new byte[] { 1, 2, 3, 4 });
        Files.writeString(tempDirectory.resolve(".ready"), "ready\n");
        Files.writeString(tempDirectory.resolve("metadata.json"), """
                {
                  "schemaVersion":1,"packageId":"package-123","packageStatus":"READY",
                  "sourceType":"DOWNLOAD_BOT","sourceAssetId":123,"sourceEventKey":"event",
                  "originalFileName":"video.mp4","videoFile":"video.mp4",
                  "contentSha256":"%s","sizeBytes":4,"mimeType":"video/mp4",
                  "storagePolicyVersion":"big-video-v1","tagStatus":"%s","tagArtifact":"tags.json",
                  "createdAt":"2026-08-15T21:30:45+08:00","updatedAt":"2026-08-15T21:38:10+08:00"
                }
                """.formatted(CONTENT_SHA256, status));
        if (readyTags) {
            Files.writeString(tempDirectory.resolve("tags.json"), """
                    {
                      "schemaVersion":1,"status":"READY","contentSha256":"%s",
                      "producer":"DOWNLOAD_BOT","provider":"ollama","model":"test-model",
                      "promptVersion":"media-tags-v1","inputKind":"VIDEO_THUMBNAIL",
                      "inputFingerprint":"%s","generatedAt":"2026-08-15T21:38:10+08:00",
                      "tags":[{"name":"海边日落","type":"topic","confidence":0.94}]
                    }
                    """.formatted(CONTENT_SHA256, "b".repeat(64)));
        }
        return video;
    }

    private LocalFile localFile(Path video) {
        LocalFile file = new LocalFile();
        file.setId(7L);
        file.setFilePath(video.toAbsolutePath().normalize().toString());
        file.setFileHash(CONTENT_SHA256);
        file.setMimeType("video/mp4");
        file.setTaggingStatus(0);
        return file;
    }
}
