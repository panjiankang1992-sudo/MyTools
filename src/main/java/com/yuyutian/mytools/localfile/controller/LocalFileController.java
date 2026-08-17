package com.yuyutian.mytools.localfile.controller;

import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.MessageHelper;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import com.yuyutian.mytools.localfile.service.LocalFileScanTaskService;
import com.yuyutian.mytools.localfile.service.FileMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.yuyutian.mytools.localfile.dto.ScanTask;
import com.yuyutian.mytools.localfile.dto.FileMaintenanceTask;
import com.yuyutian.mytools.localfile.dto.LocalMediaMutationRequest;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.net.URI;

/**
 * 本地文件管理 Controller。
 *
 * @author mytools
 * @since 2026-05-04
 */
@RestController
@RequestMapping("/api/localfiles")
@RequiredArgsConstructor
@Slf4j
public class LocalFileController {

    private final LocalFileService localFileService;
    private final LocalFileScanTaskService localFileScanTaskService;
    private final CloudFileService cloudFileService;
    private final FileMaintenanceService fileMaintenanceService;

    /**
     * 分页获取文件列表。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Map<String, Object>>> getFilePage(
            @RequestParam Long directoryId,
            @RequestParam Long page,
            @RequestParam Long pageSize,
            @RequestParam(required = false) String subdirectory,
            @RequestParam(required = false) String tagName,
            @RequestParam(required = false) List<String> tagNames,
            @RequestParam(defaultValue = "false") boolean matchAllTags,
            @RequestParam(required = false) String fileType,
            @RequestParam(required = false) String keyword) {
        List<LocalFile> files = localFileService.getFilePage(
                directoryId, subdirectory, tagName, tagNames, matchAllTags, fileType, keyword, page, pageSize);
        long total = localFileService.countFiles(
                directoryId, subdirectory, tagName, tagNames, matchAllTags, fileType, keyword);

        Map<String, Object> data = new HashMap<>();
        data.put("list", files);
        data.put("total", total);

        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 获取文件列表可用筛选项。
     */
    @GetMapping("/filters")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Map<String, Object>>> getFileFilterOptions(@RequestParam Long directoryId) {
        return ResponseEntity.ok(Result.success(localFileService.getFileFilterOptions(directoryId)));
    }

    /**
     * 获取文件内容，用于预览或下载。
     */
    @GetMapping("/{id}/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StreamingResponseBody> getFileContent(@PathVariable Long id,
                                                                 @RequestHeader HttpHeaders requestHeaders)
            throws java.io.IOException {
        Path path = localFileService.getReadableFilePath(id);
        String contentType = Files.probeContentType(path);
        MediaType mediaType = contentType == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(contentType);
        List<org.springframework.http.HttpRange> ranges = requestHeaders.getRange();
        if (!ranges.isEmpty()) {
            // 浏览器按需请求视频片段，避免播放前下载完整文件。
            return streamRange(path, mediaType, ranges.get(0));
        }
        StreamingResponseBody body = outputStream -> Files.copy(path, outputStream);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(Files.size(path))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" +
                        java.net.URLEncoder.encode(path.getFileName().toString(), java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    private ResponseEntity<StreamingResponseBody> streamRange(Path path, MediaType mediaType,
                                                                HttpRange range) throws java.io.IOException {
        long fileSize = Files.size(path);
        long start = range.getRangeStart(fileSize);
        long end = range.getRangeEnd(fileSize);
        long count = end - start + 1;
        StreamingResponseBody body = outputStream -> {
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                channel.position(start);
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                long remaining = count;
                // 严格限制输出长度，避免Range请求退化为完整文件下载。
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int read = channel.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    outputStream.write(buffer.array(), 0, read);
                    remaining -= read;
                }
            }
        };
        return ResponseEntity.status(org.springframework.http.HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType).contentLength(count)
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes").body(body);
    }

    /**
     * 通过 Alist 获取支持 Range 请求的视频或音频播放地址。
     */
    @GetMapping("/{id}/play")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> playFile(@PathVariable Long id,
                                         @RequestAttribute("userId") Long userId) {
        Path path = localFileService.getReadableFilePath(id);
        String normalizedPath = path.toAbsolutePath().normalize().toString();
        String localRoot = "/opt/custom";
        if (!normalizedPath.startsWith(localRoot + "/")) {
            return ResponseEntity.notFound().build();
        }

        // Alist 的 /custom 存储挂载到 /opt/custom，保持相对路径不变。
        String alistPath = "/custom" + normalizedPath.substring(localRoot.length());
        String rawUrl = cloudFileService.alistRawUrl(userId, null, alistPath);
        return ResponseEntity.status(org.springframework.http.HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(rawUrl))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }

    /**
     * 获取图片缩略图。
     */
    @GetMapping("/{id}/thumbnail")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<FileSystemResource> getThumbnail(@PathVariable Long id) {
        try {
            Path path = localFileService.getThumbnailFilePath(id);
            String contentType = Files.probeContentType(path);
            MediaType mediaType = contentType == null
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(contentType);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(Files.size(path))
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=604800")
                    .body(new FileSystemResource(path));
        } catch (java.io.IOException ex) {
            // 损坏或无法解码的图片不应影响整个图库页面。
            log.warn("生成缩略图失败，文件ID：{}", id, ex);
            return ResponseEntity.noContent().build();
        }
    }

    /**
     * 获取文件详情。
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<LocalFile>> getFileById(@PathVariable Long id) {
        LocalFile file = localFileService.getFileById(id);
        if (file == null) {
            return ResponseEntity.ok(Result.error(ErrorCode.FILE_001));
        }
        return ResponseEntity.ok(Result.success(file));
    }

    /**
     * 获取文件的标签列表。
     */
    @GetMapping("/{id}/tags")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<List<FileTag>>> getFileTags(@PathVariable Long id) {
        List<FileTag> tags = localFileService.getFileTags(id);
        return ResponseEntity.ok(Result.success(tags));
    }

    /** 使用完整集合替换文件人工标签。 */
    @PutMapping("/{id}/tags")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<List<FileTag>>> replaceFileTags(
            @PathVariable Long id, @RequestBody LocalMediaMutationRequest.Tags request) {
        return ResponseEntity.ok(Result.success(localFileService.replaceFileTags(id, request.tags())));
    }

    /** 重命名受管媒体文件。 */
    @PutMapping("/{id}/name")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> renameFile(
            @PathVariable Long id, @RequestBody LocalMediaMutationRequest.Rename request) {
        localFileService.renameFile(id, request.name());
        return ResponseEntity.ok(Result.success());
    }

    /** 移动受管媒体文件。 */
    @PutMapping("/{id}/location")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> moveFile(
            @PathVariable Long id, @RequestBody LocalMediaMutationRequest.Move request) {
        localFileService.moveFile(id, request.directoryPath());
        return ResponseEntity.ok(Result.success());
    }

    /** 删除受管媒体文件。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> deleteFile(@PathVariable Long id) {
        localFileService.deleteFile(id);
        return ResponseEntity.ok(Result.success());
    }

    /**
     * 手动触发文件打标签。
     */
    @PostMapping("/{id}/tag")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<List<FileTag>>> triggerTagging(@PathVariable Long id) {
        List<FileTag> tags = localFileService.triggerTagging(id);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.tagging"), tags));
    }

    /**
     * 获取目录列表。
     */
    @GetMapping("/directories")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<List<LocalDirectory>>> getDirectories() {
        List<LocalDirectory> directories = localFileService.getDirectories();
        return ResponseEntity.ok(Result.success(directories));
    }

    /**
     * 扫描目录。
     */
    @PostMapping("/scan")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<ScanTask>> scanDirectory(
            @RequestParam Long directoryId,
            @RequestParam(defaultValue = "false") boolean fullScan) {
        ScanTask task = localFileScanTaskService.submitScan(directoryId, fullScan);
        return ResponseEntity.accepted().body(Result.success(task));
    }

    /**
     * 获取目录扫描任务状态。
     */
    @GetMapping("/scan/tasks/{taskId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<ScanTask>> getScanTask(@PathVariable String taskId) {
        return ResponseEntity.ok(Result.success(localFileScanTaskService.getTask(taskId)));
    }

    /**
     * 提交文件去重或电子书智能整理任务。
     */
    @PostMapping("/maintenance")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<FileMaintenanceTask>> maintainFiles(
            @RequestParam Long directoryId,
            @RequestParam String mode) {
        return ResponseEntity.accepted().body(Result.success(fileMaintenanceService.submit(directoryId, mode)));
    }

    /**
     * 获取文件维护任务状态。
     */
    @GetMapping("/maintenance/tasks/{taskId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<FileMaintenanceTask>> getMaintenanceTask(@PathVariable String taskId) {
        return ResponseEntity.ok(Result.success(fileMaintenanceService.getTask(taskId)));
    }
}
