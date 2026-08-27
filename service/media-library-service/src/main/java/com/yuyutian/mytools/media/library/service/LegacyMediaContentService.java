package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.LegacyContentDatabase;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

/**
 * 从旧库只读解析已经迁移的本机文件内容。
 */
@Service
public class LegacyMediaContentService {
    private static final Path RESOURCE_ROOT = Path.of("/opt/extend/resource");
    private final MediaRepository repository;
    private final LegacyContentDatabase database;
    private final DerivedThumbnailContentService derivedThumbnailContentService;
    private final String resourceUsername;

    /**
     * 创建旧媒体内容服务。
     *
     * @param repository 新媒体仓储
     * @param database 旧库只读配置
     * @param derivedThumbnailContentService 派生缩略图内容服务
     * @param resourceUsername 迁移后的资源用户名目录
     */
    public LegacyMediaContentService(MediaRepository repository, LegacyContentDatabase database,
                                     DerivedThumbnailContentService derivedThumbnailContentService,
                                     @Value("${media-library.legacy-resource-username:yuyutian}")
                                     String resourceUsername) {
        this.repository = repository;
        this.database = database;
        this.derivedThumbnailContentService = derivedThumbnailContentService;
        if (!resourceUsername.matches("^[A-Za-z0-9._-]{1,128}$")) {
            throw new IllegalArgumentException("legacy resource username is invalid");
        }
        this.resourceUsername = resourceUsername;
    }

    /**
     * 解析当前所有者已经迁移媒体的真实文件。
     *
     * @param ownerId 所有者
     * @param mediaId 媒体标识
     * @param thumbnail 是否优先读取旧缩略图
     * @return 文件内容
     */
    public Content content(long ownerId, UUID mediaId, boolean thumbnail) {
        // 优先读取分析任务产生并登记的版本化缩略图，旧缩略图仅作为迁移期回退。
        if (thumbnail) {
            Content derived = derivedThumbnailContentService.content(ownerId, mediaId).orElse(null);
            if (derived != null) {
                return derived;
            }
            // 派生缩略图尚未生成时，图片可直接回退到资产存储中的原图。
            Content originalImage = derivedThumbnailContentService.originalImage(ownerId, mediaId).orElse(null);
            if (originalImage != null) {
                return originalImage;
            }
        }
        long legacyId = repository.legacyFileId(ownerId, mediaId)
                .orElseThrow(() -> new IllegalArgumentException("media content not found"));
        String url = "jdbc:mysql://" + database.host() + ":" + database.port() + "/" + database.database()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        try (var connection = DriverManager.getConnection(url, database.username(), database.password());
             var statement = connection.prepareStatement(
                     "SELECT file_path,thumbnail_path,mime_type,file_size FROM local_file WHERE id=?")) {
            connection.setReadOnly(true);
            statement.setLong(1, legacyId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("media content not found");
                }
                String thumbnailPath = result.getString("thumbnail_path");
                Path original = resolveMigratedPath(Path.of(result.getString("file_path")));
                String originalMimeType = result.getString("mime_type");
                Path path = thumbnail && thumbnailPath != null && !thumbnailPath.isBlank()
                        ? resolveMigratedPath(Path.of(thumbnailPath)) : original;
                long expectedSize = result.getLong("file_size");
                // 图片缩略图缺失时可回退原图；视频禁止把超大原文件伪装成缩略图返回。
                if (thumbnail && !Files.isRegularFile(path)) {
                    if (originalMimeType == null || !originalMimeType.toLowerCase().startsWith("image/")) {
                        throw new IllegalArgumentException("media thumbnail is unavailable");
                    }
                    path = original;
                }
                if (!Files.isRegularFile(path) || path.equals(original) && Files.size(path) != expectedSize) {
                    throw new IllegalArgumentException("media content is unavailable");
                }
                String detected = Files.probeContentType(path);
                String mimeType = thumbnail && !path.equals(original) ? detected : originalMimeType;
                long actualSize = Files.size(path);
                return new Content(new FileSystemResource(path),
                        mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType,
                        actualSize);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("legacy media content lookup failed", exception);
        }
    }

    private Path resolveMigratedPath(Path storedPath) {
        Path normalized = storedPath.toAbsolutePath().normalize();
        if (Files.exists(normalized) || !normalized.startsWith(RESOURCE_ROOT)) {
            return normalized;
        }
        Path relative = RESOURCE_ROOT.relativize(normalized);
        Path migrated = RESOURCE_ROOT.resolve(resourceUsername).resolve(relative).normalize();
        if (!migrated.startsWith(RESOURCE_ROOT.resolve(resourceUsername))) {
            throw new IllegalArgumentException("media content path is invalid");
        }
        return migrated;
    }

    /** 已验证的媒体文件内容。 */
    public record Content(Resource resource, String mimeType, long sizeBytes) {
    }
}
