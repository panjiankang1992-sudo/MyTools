package com.yuyutian.mytools.media.service.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.media.service.analysis.MediaPackageFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualVideoPackageServiceTest {

    @TempDir
    Path root;

    @Test
    void shouldAtomicallyPackageOnlyOrphanManualLargeVideo() throws Exception {
        LocalDirectoryMapper directoryMapper = mock(LocalDirectoryMapper.class);
        LocalFileMapper fileMapper = mock(LocalFileMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ManualVideoPackageService service = new ManualVideoPackageService(directoryMapper, fileMapper,
                new MediaPackageFileWriter(objectMapper));
        ReflectionTestUtils.setField(service, "thresholdBytes", 4L);

        Path source = Files.write(root.resolve("Family Trip.mp4"), new byte[]{1, 2, 3, 4, 5});
        Files.setLastModifiedTime(source, FileTime.from(Instant.parse("2026-08-15T04:30:20Z")));
        LocalDirectory directory = new LocalDirectory();
        directory.setDirectoryPath(root.toString());
        directory.setDirectoryType("LARGE_MEDIA");
        directory.setScanEnabled(1);
        LocalFile file = new LocalFile();
        file.setId(88L);
        file.setFilename("Family Trip.mp4");
        file.setFilePath(source.toString());
        file.setFileSize(5L);
        file.setMimeType("video/mp4");
        file.setFileHash("a".repeat(64));
        when(directoryMapper.selectByType("LARGE_MEDIA")).thenReturn(directory);
        when(fileMapper.selectActiveFilesByDirectory(root.toString())).thenReturn(List.of(file));

        assertThat(service.packagePendingVideos()).isEqualTo(1);

        Path packageDirectory;
        try (var paths = Files.list(root)) {
            packageDirectory = paths.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        assertThat(packageDirectory.getFileName().toString())
                .matches("20260815_[0-9]{6}_Family_Trip");
        assertThat(packageDirectory.resolve("Family Trip.mp4")).isRegularFile();
        assertThat(packageDirectory.resolve(".ready")).isRegularFile();
        JsonNode metadata = objectMapper.readTree(packageDirectory.resolve("metadata.json").toFile());
        assertThat(metadata.path("sourceType").asText()).isEqualTo("MANUAL_SCAN");
        assertThat(metadata.path("tagStatus").asText()).isEqualTo("SKIPPED");
        verify(fileMapper).updateFileLocation(eq(88L), eq("Family Trip.mp4"),
                matches(".*/20260815_[0-9]{6}_Family_Trip/Family Trip\\.mp4"),
                org.mockito.ArgumentMatchers.any());
    }
}
