package com.yuyutian.mytools.media.service.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.media.model.MediaPackageManifest;
import com.yuyutian.mytools.media.model.MediaTagArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 安全读取并校验 DownloadBot 媒体资源包清单。
 */
@Component
public class MediaPackageArtifactReader {

    private static final long MAX_MANIFEST_BYTES = 256 * 1024L;
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Set<String> TAG_STATUSES = Set.of("PENDING", "RUNNING", "READY", "FAILED", "SKIPPED");
    private static final Set<String> PACKAGE_STATUSES = Set.of("READY");
    private static final Set<String> SOURCE_TYPES = Set.of("DOWNLOAD_BOT", "MANUAL_SCAN");

    private final ObjectMapper objectMapper;

    /**
     * 创建资源包协议读取器。
     *
     * @param objectMapper JSON 解析器
     */
    public MediaPackageArtifactReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取资源包清单并校验完成标记和主视频路径。
     *
     * @param packageDirectory 资源包目录
     * @return 已校验的资源包清单
     */
    public MediaPackageManifest readManifest(Path packageDirectory) {
        Path root = requireDirectory(packageDirectory);
        requireRegularFile(root, ".ready");
        MediaPackageManifest manifest = readJson(
                requireRegularFile(root, "metadata.json"), MediaPackageManifest.class);
        validateManifest(manifest);

        // 主视频必须位于资源包真实目录内，符号链接不能逃逸到外部路径。
        Path video = requireRegularFile(root, manifest.videoFile());
        try {
            if (Files.size(video) != manifest.sizeBytes()) {
                throw new MediaPackageArtifactException("Media package video size does not match manifest");
            }
        } catch (IOException ex) {
            throw new MediaPackageArtifactException("Unable to inspect media package video", ex);
        }
        return manifest;
    }

    /**
     * 读取标签产物，并校验它与视频内容哈希一致。
     *
     * @param packageDirectory 资源包目录
     * @param manifest 已校验的资源包清单
     * @return 已校验的标签产物
     */
    public MediaTagArtifact readTagArtifact(Path packageDirectory, MediaPackageManifest manifest) {
        Path root = requireDirectory(packageDirectory);
        Path artifactPath = requireRegularFile(root, manifest.tagArtifact());
        MediaTagArtifact artifact = readJson(artifactPath, MediaTagArtifact.class);
        validateTagArtifact(artifact, manifest.contentSha256());
        return artifact;
    }

    private Path requireDirectory(Path packageDirectory) {
        try {
            Path root = packageDirectory.toRealPath();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new MediaPackageArtifactException("Media package path is not a directory");
            }
            return root;
        } catch (IOException ex) {
            throw new MediaPackageArtifactException("Unable to resolve media package directory", ex);
        }
    }

    private Path requireRegularFile(Path root, String relativeName) {
        validateRelativeFileName(relativeName);
        try {
            Path candidate = root.resolve(relativeName).normalize();
            Path realPath = candidate.toRealPath();
            if (!realPath.startsWith(root) || !Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new MediaPackageArtifactException("Media package file is outside the package directory");
            }
            return realPath;
        } catch (IOException ex) {
            throw new MediaPackageArtifactException("Required media package file is unavailable", ex);
        }
    }

    private <T> T readJson(Path path, Class<T> type) {
        try {
            if (Files.size(path) <= 0 || Files.size(path) > MAX_MANIFEST_BYTES) {
                throw new MediaPackageArtifactException("Media package JSON size is invalid");
            }
            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException ex) {
            throw new MediaPackageArtifactException("Unable to parse media package JSON", ex);
        }
    }

    private void validateManifest(MediaPackageManifest manifest) {
        if (manifest == null || manifest.schemaVersion() != 1) {
            throw new MediaPackageArtifactException("Unsupported media package schema version");
        }
        if (!matchesIdentifier(manifest.packageId()) || !PACKAGE_STATUSES.contains(manifest.packageStatus())) {
            throw new MediaPackageArtifactException("Media package identity or status is invalid");
        }
        if (!SOURCE_TYPES.contains(manifest.sourceType())) {
            throw new MediaPackageArtifactException("Media package source type is invalid");
        }
        if (manifest.sourceAssetId() != null && manifest.sourceAssetId() <= 0) {
            throw new MediaPackageArtifactException("Media package source asset is invalid");
        }
        if (manifest.sourceEventKey() == null || manifest.sourceEventKey().length() > 255) {
            throw new MediaPackageArtifactException("Media package source event is invalid");
        }
        validateRelativeFileName(manifest.originalFileName());
        validateRelativeFileName(manifest.videoFile());
        validateRelativeFileName(manifest.tagArtifact());
        if (!isSha256(manifest.contentSha256()) || manifest.sizeBytes() <= 0) {
            throw new MediaPackageArtifactException("Media package content identity is invalid");
        }
        if (manifest.mimeType() == null || !manifest.mimeType().startsWith("video/")
                || manifest.mimeType().length() > 255) {
            throw new MediaPackageArtifactException("Media package MIME type is invalid");
        }
        if (!matchesIdentifier(manifest.storagePolicyVersion()) || !TAG_STATUSES.contains(manifest.tagStatus())) {
            throw new MediaPackageArtifactException("Media package policy or tag status is invalid");
        }
        validateTimestamp(manifest.createdAt());
        validateTimestamp(manifest.updatedAt());
    }

    private void validateTagArtifact(MediaTagArtifact artifact, String expectedSha256) {
        if (artifact == null || artifact.schemaVersion() != 1 || !TAG_STATUSES.contains(artifact.status())) {
            throw new MediaPackageArtifactException("Tag artifact schema or status is invalid");
        }
        if (!expectedSha256.equals(artifact.contentSha256()) || !isSha256(artifact.inputFingerprint())) {
            throw new MediaPackageArtifactException("Tag artifact content identity is invalid");
        }
        validateText(artifact.producer(), 32, "producer");
        validateText(artifact.provider(), 64, "provider");
        validateText(artifact.model(), 255, "model");
        validateText(artifact.promptVersion(), 64, "promptVersion");
        validateText(artifact.inputKind(), 64, "inputKind");
        validateTimestamp(artifact.generatedAt());

        List<MediaTagArtifact.Tag> tags = artifact.tags() == null ? List.of() : artifact.tags();
        if (tags.size() > 6 || ("READY".equals(artifact.status()) && tags.isEmpty())) {
            throw new MediaPackageArtifactException("Tag artifact tag count is invalid");
        }
        for (MediaTagArtifact.Tag tag : tags) {
            validateText(tag.name(), 64, "tagName");
            validateText(tag.type(), 64, "tagType");
            if (!Double.isFinite(tag.confidence()) || tag.confidence() < 0D || tag.confidence() > 1D) {
                throw new MediaPackageArtifactException("Tag artifact confidence is invalid");
            }
        }
    }

    private void validateRelativeFileName(String value) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
                || value.contains("\u0000") || ".".equals(value) || "..".equals(value)) {
            throw new MediaPackageArtifactException("Media package file name is invalid");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException ex) {
            throw new MediaPackageArtifactException("Media package file name is invalid", ex);
        }
        if (path.isAbsolute() || path.getNameCount() != 1) {
            throw new MediaPackageArtifactException("Media package file name is invalid");
        }
    }

    private void validateText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new MediaPackageArtifactException("Tag artifact " + field + " is invalid");
        }
    }

    private void validateTimestamp(String value) {
        try {
            OffsetDateTime.parse(value);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new MediaPackageArtifactException("Media package timestamp is invalid", ex);
        }
    }

    private boolean matchesIdentifier(String value) {
        return value != null && IDENTIFIER_PATTERN.matcher(value).matches();
    }

    private boolean isSha256(String value) {
        return value != null && SHA256_PATTERN.matcher(value).matches();
    }
}
