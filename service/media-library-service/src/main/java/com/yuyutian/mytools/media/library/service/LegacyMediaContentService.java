package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.LegacyContentDatabase;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import com.yuyutian.mytools.media.library.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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

    /** 校验所有者和可用状态；缺少旧文件映射的条目从其登记的原始资产取流。 */
    public boolean writeManagedOriginal(long ownerId, UUID mediaId, String range, boolean head,
                                        HttpServletResponse response) {
        var media = repository.view(mediaId)
                .filter(item -> item.ownerId() == ownerId && "READY".equals(item.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ErrorCode.CONTENT_NOT_FOUND.code()));
        // 已迁移的旧文件继续使用原有文件资源和 Spring 的 Range 处理。
        if (repository.legacyFileId(ownerId, mediaId).isPresent()) return false;
        derivedThumbnailContentService.writeOriginal(media, range, head, response);
        return true;
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
        var media = repository.view(mediaId).filter(value -> value.ownerId() == ownerId)
                .orElseThrow(() -> new IllegalArgumentException("media content not found"));
        Long legacyId = repository.legacyFileId(ownerId, mediaId).orElse(null);
        // 扫描导入条目可能没有旧主键关联，仅为已授权媒体按摘要和长度复用相同内容的旧封面。
        if (legacyId == null && !thumbnail) throw new IllegalArgumentException("media content not found");
        String url = "jdbc:mysql://" + database.host() + ":" + database.port() + "/" + database.database()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        try (var connection = DriverManager.getConnection(url, database.username(), database.password());
             var statement = connection.prepareStatement(
                     legacyId != null ? "SELECT id,file_path,thumbnail_path,mime_type,file_size FROM local_file WHERE id=?"
                             : "SELECT id,file_path,thumbnail_path,mime_type,file_size FROM local_file WHERE file_hash=? AND file_size=? AND deleted=0 ORDER BY thumbnail_path IS NULL,id LIMIT 1")) {
            connection.setReadOnly(true);
            if (legacyId != null) statement.setLong(1, legacyId);
            else {
                statement.setString(1, media.contentSha256());
                statement.setLong(2, media.sizeBytes());
            }
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
                // 旧视频包已经保存封面时直接复用，禁止把视频原文件当成图片预览返回。
                if (thumbnail && originalMimeType != null && originalMimeType.startsWith("video/")
                        && (path.equals(original) || !Files.isRegularFile(path))) {
                    Path sharedThumbnail = RESOURCE_ROOT.resolve(".thumbnails").resolve(result.getLong("id") + ".jpg");
                    Path packagedThumbnail = original.getParent().resolve("thumbnail.jpg");
                    if (Files.isRegularFile(sharedThumbnail)) {
                        path = sharedThumbnail;
                    } else if (original.toString().contains("/big_media/") && Files.isRegularFile(packagedThumbnail)) {
                        path = packagedThumbnail;
                    } else {
                        throw new IllegalArgumentException("media thumbnail is unavailable");
                    }
                }
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

    Path resolveMigratedPath(Path storedPath) {
        Path normalized = storedPath.toAbsolutePath().normalize();
        Path userResourceRoot = RESOURCE_ROOT.resolve(resourceUsername).normalize();
        // 历史缩略图仍存放在公共目录，迁移记录可能误加了用户名层；沿用唯一旧文件标识。
        Path sharedThumbnails = RESOURCE_ROOT.resolve(".thumbnails");
        if (normalized.startsWith(sharedThumbnails)) return normalized;
        Path scopedThumbnails = userResourceRoot.resolve(".thumbnails");
        if (normalized.startsWith(scopedThumbnails) && !Files.isRegularFile(normalized)) {
            return sharedThumbnails.resolve(scopedThumbnails.relativize(normalized));
        }
        // 新目录已经包含用户名层时必须原样解析，不能在文件暂时缺失时重复拼接用户名。
        if (normalized.startsWith(userResourceRoot)) {
            return normalized;
        }
        if (!normalized.startsWith(RESOURCE_ROOT)) {
            return normalized;
        }
        // 旧库仍可能保存 resource/media/...，只为这种旧路径补齐用户名层。
        Path relative = RESOURCE_ROOT.relativize(normalized);
        Path migrated = userResourceRoot.resolve(relative).normalize();
        if (!migrated.startsWith(userResourceRoot)) {
            throw new IllegalArgumentException("media content path is invalid");
        }
        return migrated;
    }

    /** 已验证的媒体文件内容。 */
    public record Content(Resource resource, String mimeType, long sizeBytes) {
    }
}
