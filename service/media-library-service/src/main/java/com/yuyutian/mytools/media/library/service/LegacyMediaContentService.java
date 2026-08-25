package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.LegacyContentDatabase;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import org.springframework.core.io.FileSystemResource;
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
    private final MediaRepository repository;
    private final LegacyContentDatabase database;

    /**
     * 创建旧媒体内容服务。
     *
     * @param repository 新媒体仓储
     * @param database 旧库只读配置
     */
    public LegacyMediaContentService(MediaRepository repository, LegacyContentDatabase database) {
        this.repository = repository;
        this.database = database;
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
                Path original = Path.of(result.getString("file_path")).toAbsolutePath().normalize();
                Path path = thumbnail && thumbnailPath != null && !thumbnailPath.isBlank()
                        ? Path.of(thumbnailPath).toAbsolutePath().normalize() : original;
                long expectedSize = result.getLong("file_size");
                // 原文件必须复核迁移时大小；缩略图缺失时回退原图。
                if (thumbnail && !Files.isRegularFile(path)) {
                    path = original;
                }
                if (!Files.isRegularFile(path) || path.equals(original) && Files.size(path) != expectedSize) {
                    throw new IllegalArgumentException("media content is unavailable");
                }
                String detected = Files.probeContentType(path);
                String mimeType = thumbnail && !path.equals(original) ? detected : result.getString("mime_type");
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

    /** 已验证的媒体文件内容。 */
    public record Content(FileSystemResource resource, String mimeType, long sizeBytes) {
    }
}
