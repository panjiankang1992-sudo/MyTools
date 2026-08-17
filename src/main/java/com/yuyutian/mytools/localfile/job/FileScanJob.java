package com.yuyutian.mytools.localfile.job;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.media.service.importer.MediaPackageTagImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地文件扫描任务。
 * 定期扫描指定目录下的文件并记录到数据库。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileScanJob {

    private final LocalFileMapper localFileMapper;
    private final MediaPackageTagImportService mediaPackageTagImportService;

    /** 扫描目录路径 */
    @Value("${file.scan.path:D:/MyFiles}")
    private String scanPath;

    /** 支持的文件扩展名 */
    @Value("${file.scan.extensions:jpg,jpeg,png,gif,bmp,webp,mp4,avi,mov,wmv,mp3,wav,flac,txt,md,pdf,doc,docx}")
    private String extensions;

    /** 缩略图目录 */
    @Value("${file.scan.thumbnail-path:D:/MyFiles/.thumbnails}")
    private String thumbnailPath;

    /** 每次扫描的最大文件数 */
    @Value("${file.scan.batch-size:100}")
    private int batchSize;

    /** 哈希算法 */
    private static final String HASH_ALGORITHM = "SHA-256";

    /** MIME类型映射 */
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp"));
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "avi", "mov", "wmv", "mkv", "flv"));
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "wav", "flac", "aac", "ogg", "m4a"));

    /**
     * 每天凌晨2点执行文件扫描。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scanFiles() {
        log.info("开始扫描文件目录: {}", scanPath);
        Path rootPath = Paths.get(scanPath);

        if (!Files.exists(rootPath)) {
            log.warn("扫描目录不存在: {}", scanPath);
            int deletedCount = localFileMapper.markDirectoryDeleted(
                    rootPath.toAbsolutePath().normalize().toString(), LocalDateTime.now());
            log.info("目录不存在，已标记删除 {} 条文件记录", deletedCount);
            return;
        }

        try {
            int scannedCount = scanDirectory(rootPath, 0);
            int deletedCount = markMissingFiles(rootPath.toAbsolutePath().normalize().toString());
            log.info("文件扫描完成，新增或恢复文件: {} 个，标记删除: {} 个", scannedCount, deletedCount);
        } catch (Exception e) {
            log.error("文件扫描失败", e);
        }
    }

    /**
     * 递归扫描目录。
     */
    private int scanDirectory(Path path, int count) throws IOException {
        File directory = path.toFile();
        if (!directory.isDirectory()) {
            return count;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return count;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                // 跳过缩略图目录
                if (file.getName().equals(".thumbnails") || file.getName().startsWith(".")) {
                    continue;
                }
                count = scanDirectory(file.toPath(), count);
            } else {
                count = processFile(file, count);
            }

            // 达到批次限制时暂停
            if (count >= batchSize) {
                log.info("达到批次扫描限制: {}", batchSize);
                break;
            }
        }

        return count;
    }

    /**
     * 标记扫描目录中已明确不存在的文件。
     */
    private int markMissingFiles(String directoryPath) {
        List<Long> missingIds = localFileMapper.selectActiveByDirectory(directoryPath).stream()
                // 仅处理能够明确确认不存在的路径，避免权限问题造成误标记。
                .filter(file -> Files.notExists(Paths.get(file.getFilePath())))
                .map(LocalFile::getId)
                .toList();
        if (missingIds.isEmpty()) {
            return 0;
        }
        localFileMapper.markDeletedByIds(missingIds, LocalDateTime.now());
        return missingIds.size();
    }

    /**
     * 处理单个文件。
     */
    private int processFile(File file, int count) {
        String extension = getExtension(file.getName()).toLowerCase();
        if (!isSupportedExtension(extension)) {
            return count;
        }

        try {
            String absolutePath = file.toPath().toAbsolutePath().normalize().toString();
            // 定时扫描先按路径跳过已有记录，避免重复读取大文件。
            LocalFile pathRecord = localFileMapper.selectByPath(absolutePath);
            if (pathRecord != null && !Boolean.TRUE.equals(pathRecord.getDeleted())) {
                // DownloadBot 标签可能晚于视频落盘完成，定时扫描负责最终对账。
                mediaPackageTagImportService.reconcile(pathRecord);
                return count;
            }

            // 计算文件哈希
            String hash = calculateHash(file);
            if (hash == null) {
                return count;
            }

            // 创建文件记录
            LocalFile localFile = new LocalFile();
            localFile.setFilename(file.getName());
            localFile.setFilePath(absolutePath);
            localFile.setFileSize(file.length());
            localFile.setExtension(extension);
            localFile.setMimeType(getMimeType(extension));
            localFile.setFileHash(hash);
            localFile.setTaggingStatus(0); // 未打标签
            localFile.setScanTime(LocalDateTime.now());
            localFile.setCreateTime(LocalDateTime.now());
            localFile.setUpdateTime(LocalDateTime.now());

            // 文件重新出现或移动后，恢复原记录，避免唯一哈希冲突。
            // 只复用已删除记录；相同内容的不同有效路径必须分别入库。
            LocalFile existingFile = pathRecord != null ? pathRecord : localFileMapper.selectDeletedByHash(hash);
            if (existingFile != null) {
                if (Boolean.TRUE.equals(existingFile.getDeleted())) {
                    localFile.setId(existingFile.getId());
                    localFileMapper.restoreFile(localFile);
                    mediaPackageTagImportService.reconcile(localFile);
                    count++;
                }
                return count;
            }

            // 设置缩略图路径（图片和视频）
            if (IMAGE_EXTENSIONS.contains(extension)) {
                localFile.setThumbnailPath(file.getAbsolutePath()); // 图片直接使用原图
            } else if (VIDEO_EXTENSIONS.contains(extension)) {
                localFile.setThumbnailPath(findThumbnail(file));
            }

            localFileMapper.insert(localFile);
            mediaPackageTagImportService.reconcile(localFile);
            count++;

            log.debug("新增文件记录: {}", file.getName());

        } catch (Exception e) {
            log.error("处理文件失败: {}", file.getAbsolutePath(), e);
        }

        return count;
    }

    /**
     * 计算文件SHA-256哈希。
     */
    private String calculateHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            // 使用流式读取，避免新增大文件扫描时一次性占用大量内存。
            try (InputStream inputStream = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("计算文件哈希失败: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 检查是否支持的文件扩展名。
     */
    private boolean isSupportedExtension(String extension) {
        Set<String> supported = new HashSet<>(Arrays.asList(extensions.split(",")));
        return supported.contains(extension);
    }

    /**
     * 获取文件扩展名。
     */
    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot + 1);
        }
        return "";
    }

    /**
     * 根据扩展名获取MIME类型。
     */
    private String getMimeType(String extension) {
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return "image/" + extension;
        } else if (VIDEO_EXTENSIONS.contains(extension)) {
            return "video/" + extension;
        } else if (AUDIO_EXTENSIONS.contains(extension)) {
            return "audio/" + extension;
        } else if ("txt".equals(extension)) {
            return "text/plain";
        } else if ("md".equals(extension)) {
            return "text/markdown";
        } else if ("pdf".equals(extension)) {
            return "application/pdf";
        } else if ("doc".equals(extension)) {
            return "application/msword";
        } else if ("docx".equals(extension)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "application/octet-stream";
    }

    /**
     * 查找视频/音频文件的缩略图。
     */
    private String findThumbnail(File videoFile) {
        Path thumbnailDir = Paths.get(thumbnailPath);
        if (!Files.exists(thumbnailDir)) {
            return null;
        }

        String videoName = videoFile.getName();
        String thumbnailName = videoName.substring(0, videoName.lastIndexOf('.')) + ".jpg";
        Path thumbnail = thumbnailDir.resolve(thumbnailName);

        if (Files.exists(thumbnail)) {
            return thumbnail.toString();
        }
        return null;
    }
}
