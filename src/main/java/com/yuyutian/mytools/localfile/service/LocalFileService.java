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
import com.yuyutian.mytools.media.service.importer.MediaPackageTagImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    public List<LocalFile> getFilePage(Long directoryId, String subdirectory, String tagName,
                                       String fileType, long page, long pageSize) {
        return getFilePage(directoryId, subdirectory, tagName, List.of(), false, fileType, "", page, pageSize);
    }

    /**
     * 使用目录、类型和多标签条件分页获取文件列表。
     */
    public List<LocalFile> getFilePage(Long directoryId, String subdirectory, String tagName,
                                       List<String> tagNames, boolean matchAllTags, String fileType,
                                       String keyword, long page, long pageSize) {
        LocalDirectory directory = requireDirectory(directoryId);
        long offset = (page - 1) * pageSize;
        List<String> normalizedTagNames = normalizeTagNames(tagName, tagNames);
        List<LocalFile> files = localFileMapper.selectPageByDirectory(
                normalizeDirectoryPath(directory.getDirectoryPath()), subdirectory, normalizedTagNames,
                normalizedTagNames.size(), matchAllTags, fileType, normalizeKeyword(keyword), offset, pageSize);
        // 列表直接携带标签，页面无需逐个文件再次请求。
        files.forEach(file -> file.setTags(fileTagMapper.selectByFileId(file.getId())));
        return files;
    }

    /**
     * 统计文件总数。
     */
    public long countFiles(Long directoryId, String subdirectory, String tagName, String fileType) {
        return countFiles(directoryId, subdirectory, tagName, List.of(), false, fileType, "");
    }

    /**
     * 使用目录、类型和多标签条件统计文件总数。
     */
    public long countFiles(Long directoryId, String subdirectory, String tagName,
                           List<String> tagNames, boolean matchAllTags, String fileType, String keyword) {
        LocalDirectory directory = requireDirectory(directoryId);
        List<String> normalizedTagNames = normalizeTagNames(tagName, tagNames);
        return localFileMapper.countByDirectory(normalizeDirectoryPath(directory.getDirectoryPath()),
                subdirectory, normalizedTagNames, normalizedTagNames.size(), matchAllTags, fileType,
                normalizeKeyword(keyword));
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        String normalized = keyword.trim();
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100);
    }

    private List<String> normalizeTagNames(String tagName, List<String> tagNames) {
        List<String> candidates = tagNames == null || tagNames.isEmpty()
                ? (tagName == null ? List.of() : List.of(tagName))
                : tagNames;
        return candidates.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty() && value.length() <= 32)
                .distinct()
                .limit(20)
                .toList();
    }

    /**
     * 获取文件列表筛选项。
     */
    public Map<String, Object> getFileFilterOptions(Long directoryId) {
        LocalDirectory directory = requireDirectory(directoryId);
        String directoryPath = normalizeDirectoryPath(directory.getDirectoryPath());
        Set<String> subdirectories = new java.util.TreeSet<>();
        for (String filePath : localFileMapper.selectPathsByDirectory(directoryPath)) {
            Path relativePath = Paths.get(directoryPath).relativize(Paths.get(filePath));
            Path parent = relativePath.getParent();
            subdirectories.add(parent == null ? "" : parent.toString().replace('\\', '/'));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("directories", new ArrayList<>(subdirectories));
        options.put("tags", localFileMapper.selectTagNamesByDirectory(directoryPath));
        options.put("fileTypes", List.of("IMAGE", "VIDEO", "AUDIO"));
        return options;
    }

    /**
     * 获取经过目录边界校验的文件路径。
     */
    public Path getReadableFilePath(Long fileId) {
        LocalFile file = localFileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.FILE_001);
        }

        Path recordedPath = Paths.get(file.getFilePath()).toAbsolutePath().normalize();
        List<Path> managedRoots = managedRoots();
        Path filePath = resolveManagedFilePath(recordedPath, managedRoots);
        if (filePath == null) {
            throw new BusinessException(ErrorCode.FILE_001);
        }
        return filePath;
    }

    private List<Path> managedRoots() {
        List<Path> roots = new ArrayList<>(localDirectoryMapper.selectAll().stream()
                .map(LocalDirectory::getDirectoryPath)
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList());
        Path configuredRoot = Paths.get(scanPath).toAbsolutePath().normalize();
        roots.add(configuredRoot);
        roots.add(configuredRoot.resolve("media"));
        roots.add(configuredRoot.resolve("ebook"));
        roots.add(configuredRoot.resolve("big_media"));
        return roots.stream().distinct().toList();
    }

    private Path resolveManagedFilePath(Path recordedPath, List<Path> managedRoots) {
        if (managedRoots.stream().anyMatch(recordedPath::startsWith) && Files.isRegularFile(recordedPath)) {
            return recordedPath;
        }
        for (Path root : managedRoots) {
            Path suffixCandidate = candidateUsingRootSuffix(recordedPath, root);
            if (suffixCandidate != null && Files.isRegularFile(suffixCandidate)) return suffixCandidate;
            Path filename = recordedPath.getFileName();
            if (filename == null) continue;
            Path directCandidate = root.resolve(filename).toAbsolutePath().normalize();
            if (directCandidate.startsWith(root) && Files.isRegularFile(directCandidate)) return directCandidate;
        }
        return null;
    }

    private Path candidateUsingRootSuffix(Path recordedPath, Path root) {
        Path rootName = root.getFileName();
        if (rootName == null) return null;
        for (int index = recordedPath.getNameCount() - 1; index >= 0; index--) {
            if (!recordedPath.getName(index).equals(rootName) || index + 1 >= recordedPath.getNameCount()) continue;
            Path relative = recordedPath.subpath(index + 1, recordedPath.getNameCount());
            Path candidate = root.resolve(relative).toAbsolutePath().normalize();
            return candidate.startsWith(root) ? candidate : null;
        }
        return null;
    }

    /**
     * 获取或生成图片缩略图。
     */
    public Path getThumbnailFilePath(Long fileId) throws IOException {
        Path source = getReadableFilePath(fileId);
        String mimeType = Files.probeContentType(source);
        if (mimeType != null && mimeType.startsWith("video/")) {
            return generateVideoThumbnail(source, fileId);
        }
        if (mimeType == null || !mimeType.startsWith("image/")) {
            return source;
        }

        Path thumbnailDirectory = Paths.get(thumbnailPath).toAbsolutePath().normalize();
        Files.createDirectories(thumbnailDirectory);
        Path target = thumbnailDirectory.resolve(fileId + ".jpg");
        if (Files.isRegularFile(target) && Files.size(target) > 2
                && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0) {
            return target;
        }

        BufferedImage original;
        try {
            original = ImageIO.read(source.toFile());
        } catch (IOException ex) {
            // ImageIO无法解码或文件扩展名与真实编码不一致时，交给FFmpeg做最后一次兼容读取。
            return generateImageThumbnailWithFfmpeg(source, target);
        }
        if (original == null) {
            // Java ImageIO 不支持 WebP 等格式时，使用 FFmpeg 统一转为 JPEG。
            return generateImageThumbnailWithFfmpeg(source, target);
        }

        int maximumEdge = 640;
        double scale = Math.min(1D, Math.min(
                (double) maximumEdge / original.getWidth(),
                (double) maximumEdge / original.getHeight()));
        int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(original, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        writeJpegAtomically(thumbnail, thumbnailDirectory, target, fileId);
        return target;
    }

    private Path generateImageThumbnailWithFfmpeg(Path source, Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + "-", ".jpg");
        try {
            runThumbnailProcess(new ProcessBuilder("ffmpeg", "-y", "-i", source.toString(),
                    "-frames:v", "1", "-vf",
                    "scale=640:-2:force_original_aspect_ratio=decrease:out_range=full,format=yuvj420p",
                    "-q:v", "3", temporary.toString()), temporary, "Image");
            moveThumbnailAtomically(temporary, target);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeJpegAtomically(BufferedImage thumbnail, Path directory, Path target, Long fileId)
            throws IOException {
        Path temporary = Files.createTempFile(directory, fileId + "-", ".jpg");
        try {
            if (!ImageIO.write(thumbnail, "jpg", temporary.toFile()) || Files.size(temporary) <= 2) {
                throw new IOException("Image thumbnail generation failed");
            }
            moveThumbnailAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveThumbnailAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void runThumbnailProcess(ProcessBuilder builder, Path output, String mediaType) throws IOException {
        Process process = builder.redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(mediaType + " thumbnail generation timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(mediaType + " thumbnail generation interrupted", ex);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 2) {
            throw new IOException(mediaType + " thumbnail generation failed");
        }
    }

    /**
     * 生成文件缩略图并保存标准缩略图路径。
     */
    public Path generateAndPersistThumbnail(Long fileId) throws IOException {
        Path generatedPath = getThumbnailFilePath(fileId);
        Path thumbnailDirectory = Paths.get(thumbnailPath).toAbsolutePath().normalize();
        if (!generatedPath.toAbsolutePath().normalize().startsWith(thumbnailDirectory)) {
            throw new IOException("Generated thumbnail is outside thumbnail directory");
        }
        localFileMapper.updateThumbnailPath(fileId, generatedPath.toString(), LocalDateTime.now());
        return generatedPath;
    }

    private Path generateVideoThumbnail(Path source, Long fileId) throws IOException {
        Path thumbnailDirectory = Paths.get(thumbnailPath).toAbsolutePath().normalize();
        Files.createDirectories(thumbnailDirectory);
        Path target = thumbnailDirectory.resolve(fileId + ".jpg");
        if (Files.isRegularFile(target) && Files.size(target) > 2
                && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0) {
            return target;
        }

        Path temporary = Files.createTempFile(thumbnailDirectory, fileId + "-", ".jpg");
        try {
            List<String> seekSeconds = List.of("0", "3", "10", "30");
            for (int index = 0; index < seekSeconds.size(); index++) {
                runThumbnailProcess(new ProcessBuilder("ffmpeg", "-y", "-ss", seekSeconds.get(index),
                        "-i", source.toString(), "-an", "-sn", "-dn", "-frames:v", "1", "-vf",
                        "scale=640:-2:force_original_aspect_ratio=decrease:out_range=full,format=yuvj420p",
                        "-q:v", "3", temporary.toString()), temporary, "Video");
                // 首帧为黑场时继续向后采样；无法解码像素时保留FFmpeg已验证的输出。
                if (!isMostlyBlack(temporary) || index == seekSeconds.size() - 1) break;
            }
            moveThumbnailAtomically(temporary, target);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean isMostlyBlack(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null || image.getWidth() == 0 || image.getHeight() == 0) return false;
            int stepX = Math.max(1, image.getWidth() / 80);
            int stepY = Math.max(1, image.getHeight() / 45);
            long sampled = 0;
            long dark = 0;
            for (int y = 0; y < image.getHeight(); y += stepY) {
                for (int x = 0; x < image.getWidth(); x += stepX) {
                    int rgb = image.getRGB(x, y);
                    int luminance = ((rgb >> 16 & 0xff) * 299 + (rgb >> 8 & 0xff) * 587
                            + (rgb & 0xff) * 114) / 1000;
                    if (luminance < 18) dark++;
                    sampled++;
                }
            }
            return sampled > 0 && dark / (double) sampled >= 0.96D;
        } catch (IOException ex) {
            return false;
        }
    }

    private LocalDirectory requireDirectory(Long directoryId) {
        LocalDirectory directory = localDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            throw new BusinessException(ErrorCode.FILE_002);
        }
        return directory;
    }

    private String normalizeDirectoryPath(String directoryPath) {
        return Paths.get(directoryPath).toAbsolutePath().normalize().toString().replaceAll("/+$", "");
    }

    /**
     * 获取文件的所有标签。
     */
    public List<FileTag> getFileTags(Long fileId) {
        return fileTagMapper.selectByFileId(fileId);
    }

    /**
     * 使用人工标签完整替换文件标签集合。
     */
    @Transactional
    public List<FileTag> replaceFileTags(Long fileId, List<String> names) {
        if (localFileMapper.selectById(fileId) == null || names == null || names.size() > 32) {
            throw new BusinessException(ErrorCode.FILE_005);
        }
        List<String> normalized = names.stream().map(String::trim).distinct().toList();
        if (normalized.stream().anyMatch(name -> name.isEmpty() || name.length() > 32
                || name.matches(".*[\\p{Cntrl}].*"))) {
            throw new BusinessException(ErrorCode.FILE_005);
        }
        fileTagMapper.deleteByFileId(fileId);
        LocalDateTime now = LocalDateTime.now();
        List<FileTag> tags = normalized.stream().map(name -> {
            FileTag tag = new FileTag();
            tag.setFileId(fileId);
            tag.setTagName(name);
            tag.setTagType("user");
            tag.setConfidence(1D);
            tag.setTaggingTime(now);
            tag.setCreateTime(now);
            return tag;
        }).toList();
        if (!tags.isEmpty()) fileTagMapper.batchInsert(tags);
        return tags;
    }

    /**
     * 在受管媒体目录边界内重命名文件。
     */
    @Transactional
    public void renameFile(Long fileId, String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")) {
            throw new BusinessException(ErrorCode.FILE_005);
        }
        LocalFile file = requireMutableFile(fileId);
        Path source = getReadableFilePath(fileId);
        Path target = source.resolveSibling(name).normalize();
        if (!target.getParent().equals(source.getParent())) throw new BusinessException(ErrorCode.FILE_005);
        moveManagedFile(file, source, target);
    }

    /**
     * 在文件所属媒体根目录内移动文件。
     */
    @Transactional
    public void moveFile(Long fileId, String directoryPath) {
        if (directoryPath == null || !directoryPath.startsWith("/") || directoryPath.contains("\\")) {
            throw new BusinessException(ErrorCode.FILE_005);
        }
        LocalFile file = requireMutableFile(fileId);
        Path source = getReadableFilePath(fileId);
        Path root = localDirectoryMapper.selectAll().stream().map(LocalDirectory::getDirectoryPath).map(Paths::get)
                .map(Path::toAbsolutePath).map(Path::normalize).filter(source::startsWith)
                .max(java.util.Comparator.comparingInt(Path::getNameCount))
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_005));
        Path targetDirectory = root.resolve(directoryPath.substring(1)).normalize();
        if (!targetDirectory.startsWith(root) || !Files.isDirectory(targetDirectory)) {
            throw new BusinessException(ErrorCode.FILE_010);
        }
        moveManagedFile(file, source, targetDirectory.resolve(source.getFileName()).normalize());
    }

    /**
     * 删除受管媒体文件并软删除索引记录。
     */
    @Transactional
    public void deleteFile(Long fileId) {
        Path source = getReadableFilePath(fileId);
        try {
            Files.delete(source);
            localFileMapper.markDeletedByIds(List.of(fileId), LocalDateTime.now());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.FILE_003);
        }
    }

    private LocalFile requireMutableFile(Long fileId) {
        LocalFile file = localFileMapper.selectById(fileId);
        if (file == null) throw new BusinessException(ErrorCode.FILE_001);
        return file;
    }

    private void moveManagedFile(LocalFile file, Path source, Path target) {
        if (Files.exists(target)) throw new BusinessException(ErrorCode.FILE_004);
        try {
            Files.move(source, target);
            localFileMapper.updateFileLocation(file.getId(), target.getFileName().toString(), target.toString(),
                    LocalDateTime.now());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.FILE_005);
        }
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
            throw new BusinessException(ErrorCode.FILE_002);
        }

        log.info("开始扫描目录：{}, 全量扫描：{}", directory.getDirectoryPath(), fullScan);

        Path rootPath = Paths.get(directory.getDirectoryPath());
        if (!Files.exists(rootPath)) {
            log.warn("扫描目录不存在：{}", directory.getDirectoryPath());
            // 根目录明确不存在时，将其下全部有效记录软删除。
            int deletedCount = localFileMapper.markDirectoryDeleted(
                    normalizeDirectoryPath(directory.getDirectoryPath()), LocalDateTime.now());
            log.info("目录不存在，已标记删除 {} 条文件记录", deletedCount);
            return new ScanResult(0, 0);
        }

        try {
            int[] counts = scanDirectoryRecursively(rootPath, new int[]{0, 0},
                directory.getDirectoryType(), !fullScan);
            int deletedCount = markMissingFiles(normalizeDirectoryPath(directory.getDirectoryPath()));
            ScanResult result = new ScanResult(counts[0], counts[1]);

            // 更新最后扫描时间
            LocalDateTime now = LocalDateTime.now();
            localDirectoryMapper.updateLastScanTime(directoryId, now, now);

            log.info("目录扫描完成：扫描 {} 个文件，新增 {} 个，标记删除 {} 个",
                    result.getScannedCount(), result.getNewCount(), deletedCount);
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
            String absolutePath = file.toPath().toAbsolutePath().normalize().toString();
            // 增量扫描先按路径跳过已有记录，避免读取文件内容和计算哈希。
            LocalFile pathRecord = localFileMapper.selectByPath(absolutePath);
            if (skipExisting && pathRecord != null && !Boolean.TRUE.equals(pathRecord.getDeleted())) {
                // DownloadBot 可能在视频首次扫描后才完成标签，增量扫描仍需对账伴生标签。
                mediaPackageTagImportService.reconcile(pathRecord);
                return counts;
            }

            // 计算文件哈希
            String hash = calculateHash(file);
            if (hash == null) {
                return counts;
            }

            // 创建或更新文件记录
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

            // 只按哈希恢复已删除记录，相同内容的不同有效路径应分别展示。
            LocalFile existingFile = pathRecord != null ? pathRecord : localFileMapper.selectDeletedByHash(hash);
            if (existingFile != null && Boolean.TRUE.equals(existingFile.getDeleted())) {
                localFile.setId(existingFile.getId());
                localFileMapper.restoreFile(localFile);
                mediaPackageTagImportService.reconcile(localFile);
                counts[0]++;
                log.info("恢复已删除文件记录：{}", absolutePath);
                return counts;
            }
            if (existingFile != null && skipExisting) {
                return counts;
            }

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
            // 资源包标签可直接复用；普通人工文件继续保持待处理并进入 MyTools 标签队列。
            mediaPackageTagImportService.reconcile(localFile);
            counts[0]++; // 扫描计数

            log.debug("处理文件：{}", file.getName());

        } catch (Exception e) {
            log.error("处理文件失败：{}", file.getAbsolutePath(), e);
        }

        return counts;
    }

    /**
     * 将数据库中已明确不存在的文件标记为已删除。
     */
    private int markMissingFiles(String directoryPath) {
        List<Long> missingIds = localFileMapper.selectActiveByDirectory(directoryPath).stream()
                // Files.notExists 仅在能够明确确认不存在时返回 true，权限异常不会误删记录。
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
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file.toPath()),
                MessageDigest.getInstance("SHA-256"))) {
            byte[] buffer = new byte[1024 * 1024];
            while (input.read(buffer) != -1) {
                // 流式读取大文件，避免一次性占用与文件大小相同的内存。
            }
            byte[] hashBytes = input.getMessageDigest().digest();
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
