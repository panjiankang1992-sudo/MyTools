package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import com.yuyutian.mytools.localfile.service.ResourceStorageGuard;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import com.yuyutian.mytools.reader.model.EbookImportModels;
import com.yuyutian.mytools.reader.task.ReaderImportSidecarRequested;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 将本地文件或书源正文安全导入MyTools电子书目录。
 */
@Service
@RequiredArgsConstructor
public class EbookImportService {
    private static final long MAX_UPLOAD_SIZE = 500L * 1024 * 1024;
    private static final long TASK_TTL_MILLIS = Duration.ofHours(12).toMillis();
    private static final Pattern SAFE_EXTENSION = Pattern.compile("(?i)txt|epub|pdf|mobi|azw3|cbz|zip");
    private static final Pattern INVALID_FILENAME = Pattern.compile("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]");
    private static final Set<String> RUNNING_STATUSES = Set.of("QUEUED", "RUNNING");

    private final LocalDirectoryMapper localDirectoryMapper;
    private final LocalFileService localFileService;
    private final ResourceStorageGuard resourceStorageGuard;
    private final BookSourceRuntimeReaderService readerService;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, MutableTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 保存用户主动选择的电子书文件。
     *
     * @param directoryId 电子书目录ID
     * @param file 上传文件
     * @param requestedName 客户端文件名
     * @return 上传结果
     */
    public EbookImportModels.UploadResult upload(Long directoryId, MultipartFile file, String requestedName) {
        if (file == null || file.isEmpty() || file.getSize() < 1 || file.getSize() > MAX_UPLOAD_SIZE) {
            throw new BusinessException(ErrorCode.READER_012);
        }
        LocalDirectory directory = requireDirectory(directoryId);
        Path root = requireWritableRoot(directory);
        String fileName = safeFileName(requestedName == null || requestedName.isBlank()
                ? file.getOriginalFilename() : requestedName);
        Path target = uniqueTarget(root, fileName);
        Path temporary = root.resolve(".upload-" + UUID.randomUUID() + ".tmp").normalize();
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(temporary) != file.getSize()) throw new IOException("Upload size mismatch");
            moveAtomically(temporary, target);
            scheduleIndex(directory.getId());
            return new EbookImportModels.UploadResult(target.getFileName().toString(), Files.size(target));
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new BusinessException(ErrorCode.READER_013);
        }
    }

    /**
     * 启动书源图书完整下载任务。
     *
     * @param userId 用户ID
     * @param directoryId 电子书目录ID
     * @param request 书源图书请求
     * @return 任务快照
     */
    public EbookImportModels.Task startSourceImport(Long userId, Long directoryId,
                                                     EbookImportModels.SourceRequest request) {
        cleanupExpiredTasks();
        long running = tasks.values().stream().filter(task -> task.userId.equals(userId)
                && RUNNING_STATUSES.contains(task.status)).count();
        if (running >= 2) throw new BusinessException(ErrorCode.READER_008);
        LocalDirectory directory = requireDirectory(directoryId);
        MutableTask task = new MutableTask(UUID.randomUUID().toString(), userId,
                safeTitle(request.title()) + ".txt");
        tasks.put(task.taskId, task);
        // 旁路只复制不可变请求，新服务失败时旧导入仍继续执行。
        eventPublisher.publishEvent(new ReaderImportSidecarRequested(task.taskId, userId,
                request.sourceUrl().trim(), request.bookUrl().trim(), request.title(), request.author()));
        executor.submit(() -> importSource(task, directory, request));
        return task.snapshot();
    }

    /**
     * 查询当前用户的后台导入任务。
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 任务快照
     */
    public EbookImportModels.Task find(Long userId, String taskId) {
        cleanupExpiredTasks();
        MutableTask task = tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) throw new BusinessException(ErrorCode.READER_014);
        return task.snapshot();
    }

    /**
     * 关闭后台导入线程。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void importSource(MutableTask task, LocalDirectory directory,
                              EbookImportModels.SourceRequest request) {
        Path temporary = null;
        try {
            task.update("RUNNING", "正在读取书源目录");
            Path root = requireWritableRoot(directory);
            BookSourceRuntimeReaderModels.Catalog catalog = readerService.catalog(task.userId,
                    request.sourceUrl(), request.bookUrl());
            String title = catalog.name().isBlank() ? request.title() : catalog.name();
            task.fileName = safeTitle(title) + ".txt";
            Path target = uniqueTarget(root, task.fileName);
            temporary = root.resolve(".source-" + task.taskId + ".tmp").normalize();
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(title);
                writer.newLine();
                if (!catalog.author().isBlank()) {
                    writer.write("作者：" + catalog.author());
                    writer.newLine();
                }
                writer.newLine();
                int total = catalog.chapters().size();
                for (int index = 0; index < total; index++) {
                    BookSourceRuntimeReaderModels.Chapter chapter = catalog.chapters().get(index);
                    BookSourceRuntimeReaderModels.Content content = readerService.content(task.userId,
                            request.sourceUrl(), chapter.resourceUri(), index);
                    writer.write(chapter.title());
                    writer.newLine();
                    writer.newLine();
                    writer.write(content.text());
                    writer.newLine();
                    writer.newLine();
                    if (index == total - 1 || index % 10 == 9) {
                        task.update("RUNNING", "已下载 " + (index + 1) + "/" + total + " 章");
                    }
                }
            }
            moveAtomically(temporary, target);
            temporary = null;
            localFileService.scanDirectory(directory.getId(), false);
            task.fileName = target.getFileName().toString();
            task.update("COMPLETED", "已保存到远程书库");
        } catch (RuntimeException | IOException exception) {
            deleteQuietly(temporary);
            task.update("FAILED", "书源图书下载失败");
        }
    }

    private void scheduleIndex(Long directoryId) {
        executor.submit(() -> {
            try {
                localFileService.scanDirectory(directoryId, false);
            } catch (RuntimeException ignored) {
                // 文件已经安全落盘，定时扫描会继续完成数据库索引。
            }
        });
    }

    private LocalDirectory requireDirectory(Long directoryId) {
        LocalDirectory directory = directoryId == null ? localDirectoryMapper.selectByType("EBOOK")
                : localDirectoryMapper.selectById(directoryId);
        if (directory == null || !"EBOOK".equals(directory.getDirectoryType())) {
            throw new BusinessException(ErrorCode.FILE_010);
        }
        return directory;
    }

    private Path requireWritableRoot(LocalDirectory directory) {
        Path root = Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize();
        resourceStorageGuard.requireAvailableForCleanup(root);
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.FILE_012);
        }
        if (!Files.isDirectory(root) || !Files.isWritable(root)) throw new BusinessException(ErrorCode.FILE_012);
        return root;
    }

    private String safeFileName(String value) {
        if (value == null) throw new BusinessException(ErrorCode.READER_012);
        String normalized = INVALID_FILENAME.matcher(value.strip()).replaceAll("_");
        int dot = normalized.lastIndexOf('.');
        if (dot < 1 || dot == normalized.length() - 1 || normalized.length() > 300 ||
                !SAFE_EXTENSION.matcher(normalized.substring(dot + 1)).matches()) {
            throw new BusinessException(ErrorCode.READER_012);
        }
        return normalized;
    }

    private String safeTitle(String value) {
        String normalized = INVALID_FILENAME.matcher(value == null ? "" : value.strip()).replaceAll("_")
                .replaceAll("\\s+", " ");
        if (normalized.isBlank()) normalized = "Imported book";
        return normalized.substring(0, Math.min(180, normalized.length()));
    }

    private Path uniqueTarget(Path root, String fileName) {
        Path initial = root.resolve(fileName).normalize();
        if (!initial.startsWith(root)) throw new BusinessException(ErrorCode.FILE_005);
        if (!Files.exists(initial)) return initial;
        int dot = fileName.lastIndexOf('.');
        String base = fileName.substring(0, dot);
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        for (int index = 2; index <= 9999; index++) {
            Path candidate = root.resolve(base + " (" + index + ")" + extension).normalize();
            if (!Files.exists(candidate)) return candidate;
        }
        throw new BusinessException(ErrorCode.FILE_004);
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void cleanupExpiredTasks() {
        long cutoff = System.currentTimeMillis() - TASK_TTL_MILLIS;
        tasks.entrySet().removeIf(entry -> !RUNNING_STATUSES.contains(entry.getValue().status)
                && entry.getValue().updatedAt < cutoff);
    }

    private static final class MutableTask {
        private final String taskId;
        private final Long userId;
        private volatile String status = "QUEUED";
        private volatile String fileName;
        private volatile String message = "等待后台下载";
        private volatile long updatedAt = System.currentTimeMillis();

        private MutableTask(String taskId, Long userId, String fileName) {
            this.taskId = taskId;
            this.userId = userId;
            this.fileName = fileName;
        }

        private void update(String status, String message) {
            this.status = status;
            this.message = message;
            this.updatedAt = System.currentTimeMillis();
        }

        private EbookImportModels.Task snapshot() {
            return new EbookImportModels.Task(taskId, status, fileName, message, updatedAt);
        }
    }
}
