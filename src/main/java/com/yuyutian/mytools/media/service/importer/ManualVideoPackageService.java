package com.yuyutian.mytools.media.service.importer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.media.service.analysis.MediaPackageFileWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 将人工扫描到的孤立大视频规范化为与 DownloadBot 相同的媒体资源包。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualVideoPackageService {

    private static final DateTimeFormatter DIRECTORY_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern INVALID_NAME = Pattern.compile("[^\\p{L}\\p{N}._-]+");
    private static final int BATCH_SIZE = 10;

    private final LocalDirectoryMapper directoryMapper;
    private final LocalFileMapper fileMapper;
    private final MediaPackageFileWriter fileWriter;

    @Value("${media.big-video-threshold-bytes:52428800}")
    private long thresholdBytes;

    /**
     * 整理一批尚未资源包化的人工大视频。
     *
     * @return 成功整理数量
     */
    public int packagePendingVideos() {
        LocalDirectory directory = directoryMapper.selectByType("LARGE_MEDIA");
        if (directory == null || directory.getScanEnabled() == null || directory.getScanEnabled() != 1) return 0;
        Path root = Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize();
        List<LocalFile> candidates = fileMapper.selectActiveFilesByDirectory(root.toString()).stream()
                .filter(file -> file.getId() != null && file.getFilePath() != null)
                .filter(file -> file.getMimeType() != null && file.getMimeType().toLowerCase(Locale.ROOT)
                        .startsWith("video/"))
                .filter(file -> file.getFileSize() != null && file.getFileSize() >= thresholdBytes)
                .filter(file -> file.getFileHash() != null && file.getFileHash().matches("[0-9a-f]{64}"))
                .filter(file -> !publishedPackage(file))
                .sorted(Comparator.comparing(LocalFile::getUpdateTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(BATCH_SIZE).toList();
        int packaged = 0;
        for (LocalFile file : candidates) {
            try {
                if (packageOne(root, file)) packaged++;
            } catch (IOException ex) {
                log.warn("人工大视频资源包整理失败：fileId={}", file.getId(), ex);
            }
        }
        return packaged;
    }

    private boolean packageOne(Path configuredRoot, LocalFile file) throws IOException {
        Path source = Path.of(file.getFilePath()).toAbsolutePath().normalize();
        if (!source.startsWith(configuredRoot) || !Files.isRegularFile(source)) return false;
        Path parent = source.getParent();
        if (parent == null || Files.isRegularFile(parent.resolve(".ready"))) return false;
        FileTime modifiedAt = Files.getLastModifiedTime(source);
        String time = DIRECTORY_TIME.format(modifiedAt.toInstant().atZone(ZoneId.systemDefault()));
        String targetName = time + "_" + brief(file.getFilename());
        Path target = availableTarget(parent, targetName, file.getId());
        if (!target.startsWith(configuredRoot)) return false;
        Path staging = parent.resolve(".media-package-" + UUID.randomUUID()).normalize();
        Files.createDirectory(staging);
        Path stagedVideo = staging.resolve(source.getFileName());
        try {
            moveAtomic(source, stagedVideo);
            ObjectNode metadata = metadata(file, source.getFileName().toString());
            fileWriter.writeJson(staging.resolve("metadata.json"), metadata);
            moveAtomic(staging, target);
            fileWriter.writeText(target.resolve(".ready"), "ready\n");
            Path finalVideo = target.resolve(source.getFileName());
            fileMapper.updateFileLocation(file.getId(), finalVideo.getFileName().toString(), finalVideo.toString(),
                    java.time.LocalDateTime.now());
            return true;
        } catch (IOException ex) {
            // 发布前失败时尽量将主视频恢复到人工原始位置，避免留下半成品。
            Path staged = Files.isDirectory(staging) ? stagedVideo : target.resolve(source.getFileName());
            if (!Files.exists(source) && Files.isRegularFile(staged)) {
                try {
                    moveAtomic(staged, source);
                } catch (IOException restoreError) {
                    ex.addSuppressed(restoreError);
                }
            }
            deleteEmptyDirectory(staging);
            throw ex;
        }
    }

    private ObjectNode metadata(LocalFile file, String videoFile) {
        ObjectNode metadata = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        String now = Instant.now().toString();
        metadata.put("schemaVersion", 1);
        metadata.put("packageId", "manual-" + file.getId());
        metadata.put("packageStatus", "READY");
        metadata.put("sourceType", "MANUAL_SCAN");
        metadata.put("sourceAssetId", file.getId());
        metadata.put("sourceEventKey", "manual-scan:" + file.getId());
        metadata.put("originalFileName", file.getFilename());
        metadata.put("videoFile", videoFile);
        metadata.put("contentSha256", file.getFileHash());
        metadata.put("sizeBytes", file.getFileSize());
        metadata.put("mimeType", file.getMimeType());
        metadata.put("storagePolicyVersion", "manual-package-v1");
        metadata.put("tagStatus", "SKIPPED");
        metadata.put("tagArtifact", "tags.json");
        metadata.put("analysisStatus", "PENDING");
        metadata.put("createdAt", now);
        metadata.put("updatedAt", now);
        return metadata;
    }

    private boolean publishedPackage(LocalFile file) {
        Path parent = Path.of(file.getFilePath()).toAbsolutePath().normalize().getParent();
        return parent != null && Files.isRegularFile(parent.resolve(".ready"));
    }

    private String brief(String fileName) {
        String name = fileName == null ? "video" : fileName;
        int extension = name.lastIndexOf('.');
        if (extension > 0) name = name.substring(0, extension);
        name = INVALID_NAME.matcher(name.trim()).replaceAll("_").replaceAll("_+", "_");
        name = name.replaceAll("^[_-]+|[_-]+$", "");
        if (name.isBlank()) name = "video";
        if (name.length() > 40) name = name.substring(0, 40);
        return name;
    }

    private Path availableTarget(Path parent, String targetName, Long fileId) {
        Path preferred = parent.resolve(targetName).normalize();
        if (!Files.exists(preferred)) return preferred;
        return parent.resolve(targetName + "_" + fileId).normalize();
    }

    private void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException("Media package requires an atomic same-filesystem move", ex);
        }
    }

    private void deleteEmptyDirectory(Path directory) {
        try {
            if (Files.isDirectory(directory)) Files.deleteIfExists(directory);
        } catch (IOException ignored) {
        }
    }
}
