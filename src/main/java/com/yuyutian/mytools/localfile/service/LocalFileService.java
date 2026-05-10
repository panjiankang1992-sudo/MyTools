package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.dto.ScanResult;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地文件服务。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileService {

    private final LocalFileMapper localFileMapper;
    private final FileTagMapper fileTagMapper;
    private final LocalDirectoryMapper localDirectoryMapper;
    private final TaggerService taggerService;

    /** 扫描目录路径 */
    @Value("${file.scan.path:D:/MyFiles}")
    private String scanPath;

    /** 支持的文件扩展名 */
    @Value("${file.scan.extensions:jpg,jpeg,png,gif,bmp,webp,mp4,avi,mov,wmv,mp3,wav,flac,txt,md,pdf,doc,docx}")
    private String extensions;

    /** 缩略图目录 */
    @Value("${file.scan.thumbnail-path:D:/MyFiles/.thumbnails}")
    private String thumbnailPath;

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp"));
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Set.of("mp4", "avi", "mov", "wmv", "mkv", "flv"));
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Set.of("mp3", "wav", "flac", "aac", "ogg", "m4a"));

    /**
     * 获取文件详情。
     */
    public LocalFile getFileById(Long id) {
        return localFileMapper.selectById(id);
    }

    /**
     * 分页获取文件列表。
     */
    public List<LocalFile> getFilePage(long page, long pageSize) {
        long offset = (page - 1) * pageSize;
        return localFileMapper.selectPage(offset, pageSize);
    }

    /**
     * 统计文件总数。
     */
    public long countFiles() {
        return localFileMapper.count();
    }

    /**
     * 获取文件的所有标签。
     */
    public List<FileTag> getFileTags(Long fileId) {
        return fileTagMapper.selectByFileId(fileId);
    }

    /**
     * 手动触发文件打标签。
     */
    @Transactional
    public List<FileTag> triggerTagging(Long fileId) {
        LocalFile file = localFileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.FILE_001);
        }

        // 先删除旧标签
        fileTagMapper.deleteByFileId(fileId);

        // 重新打标签
        return taggerService.tagFile(file);
    }

    /**
     * 检查文件是否已存在（通过哈希）。
     */
    public boolean isFileExists(String fileHash) {
        return localFileMapper.selectByHash(fileHash) != null;
    }

    /**
     * 获取所有目录列表。
     */
    public List<LocalDirectory> getDirectories() {
        return localDirectoryMapper.selectAll();
    }

    /**
     * 扫描指定目录。
     */
    @Transactional
    public ScanResult scanDirectory(Long directoryId, boolean fullScan) {
        LocalDirectory directory = localDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            throw new BusinessException("FILE_002", "目录不存在", org.springframework.http.HttpStatus.BAD_REQUEST);
        }

        log.info("开始扫描目录：{}, 全量扫描：{}", directory.getDirectoryPath(), fullScan);

        Path rootPath = Paths.get(directory.getDirectoryPath());
        if (!Files.exists(rootPath)) {
            log.warn("扫描目录不存在：{}", directory.getDirectoryPath());
            return new ScanResult(0, 0);
        }

        try {
            int[] counts = scanDirectoryRecursively(rootPath, new int[]{0, 0},
                directory.getDirectoryType(), !fullScan);
            ScanResult result = new ScanResult(counts[0], counts[1]);

            // 更新最后扫描时间
            LocalDateTime now = LocalDateTime.now();
            localDirectoryMapper.updateLastScanTime(directoryId, now, now);

            log.info("目录扫描完成：扫描 {} 个文件，新增 {} 个", result.getScannedCount(), result.getNewCount());
            return result;

        } catch (Exception e) {
            log.error("目录扫描失败", e);
            throw new RuntimeException("扫描失败：" + e.getMessage(), e);
        }
    }

    /**
     * 递归扫描目录。
     */
    private int[] scanDirectoryRecursively(Path path, int[] counts, String directoryType, boolean skipExisting) {
        File directory = path.toFile();
        if (!directory.isDirectory()) {
            return counts;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return counts;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                // 跳过缩略图目录和隐藏目录
                if (file.getName().equals(".thumbnails") || file.getName().startsWith(".")) {
                    continue;
                }
                counts = scanDirectoryRecursively(file.toPath(), counts, directoryType, skipExisting);
            } else {
                counts = processFileForDirectory(file, counts, directoryType, skipExisting);
            }
        }

        return counts;
    }

    /**
     * 处理单个文件。
     */
    private int[] processFileForDirectory(File file, int[] counts, String directoryType, boolean skipExisting) {
        String extension = getExtension(file.getName()).toLowerCase();
        if (!isSupportedExtension(extension, directoryType)) {
            return counts;
        }

        try {
            // 计算文件哈希
            String hash = calculateHash(file);
            if (hash == null) {
                return counts;
            }

            // 检查是否已存在
            LocalFile existingFile = localFileMapper.selectByHash(hash);
            if (existingFile != null) {
                if (skipExisting) {
                    return counts;
                }
                // 全量扫描时重新处理已存在的文件
            }

            // 创建或更新文件记录
            LocalFile localFile = new LocalFile();
            localFile.setFilename(file.getName());
            localFile.setFilePath(file.getAbsolutePath());
            localFile.setFileSize(file.length());
            localFile.setExtension(extension);
            localFile.setMimeType(getMimeType(extension));
            localFile.setFileHash(hash);
            localFile.setTaggingStatus(0); // 未打标签
            localFile.setScanTime(LocalDateTime.now());
            localFile.setCreateTime(LocalDateTime.now());
            localFile.setUpdateTime(LocalDateTime.now());

            // 设置缩略图路径（图片和视频）
            if (IMAGE_EXTENSIONS.contains(extension)) {
                localFile.setThumbnailPath(file.getAbsolutePath());
            } else if (VIDEO_EXTENSIONS.contains(extension)) {
                localFile.setThumbnailPath(findThumbnail(file));
            }

            if (existingFile == null) {
                localFileMapper.insert(localFile);
                counts[1]++; // 新增计数
            } else {
                localFile.setId(existingFile.getId());
                // 更新现有记录
            }
            counts[0]++; // 扫描计数

            log.debug("处理文件：{}", file.getName());

        } catch (Exception e) {
            log.error("处理文件失败：{}", file.getAbsolutePath(), e);
        }

        return counts;
    }

    /**
     * 检查是否支持的文件扩展名（根据目录类型过滤）。
     */
    private boolean isSupportedExtension(String extension, String directoryType) {
        Set<String> supported = new HashSet<>(Set.of(extensions.split(",")));
        if (!supported.contains(extension)) {
            return false;
        }

        // 根据目录类型进一步过滤
        if ("MULTIMEDIA".equals(directoryType)) {
            return IMAGE_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension) || AUDIO_EXTENSIONS.contains(extension);
        } else if ("EBOOK".equals(directoryType)) {
            return Set.of("pdf", "doc", "docx", "txt", "md", "epub", "mobi").contains(extension);
        } else if ("LARGE_MEDIA".equals(directoryType)) {
            // 大文件多媒体：只包含大文件
            return IMAGE_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension);
        }

        return true;
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
     * 根据扩展名获取 MIME 类型。
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
     * 计算文件 SHA-256 哈希。
     */
    private String calculateHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            byte[] hashBytes = digest.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("计算文件哈希失败：{}", file.getAbsolutePath(), e);
            return null;
        }
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
