package com.yuyutian.mytools.cloudfile.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.cloudfile.model.*;
import com.yuyutian.mytools.common.MessageHelper;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequiredArgsConstructor
public class CloudFileController {

    private final CloudFileService cloudFileService;
    private final JwtUtils jwtUtils;

    @GetMapping("/api/cloud/files")
    public ResponseEntity<Result<CloudFileListResponse>> listFiles(
            @RequestHeader("Authorization") String auth,
            @RequestParam(value = "path", defaultValue = "/") String path,
            @RequestParam(value = "depth", defaultValue = "1") int depth,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        CloudFileListResponse resp = cloudFileService.listFiles(userId, accountId, decode(path), depth);
        return ResponseEntity.ok(Result.success(resp));
    }

    @GetMapping("/api/cloud/file")
    public ResponseEntity<?> getFile(
            @RequestHeader("Authorization") String auth,
            @RequestParam("path") String path,
            @RequestParam(value = "preview", defaultValue = "false") boolean preview,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        String decodedPath = decode(path);
        if (preview) {
            String content = cloudFileService.getFileContent(userId, accountId, decodedPath);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        } else {
            byte[] bytes = cloudFileService.downloadFile(userId, accountId, decodedPath);
            String filename = decodedPath.substring(decodedPath.lastIndexOf('/') + 1);
            MediaType mediaType = detectMediaType(filename);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + filename + "\"")
                    .body(new ByteArrayResource(bytes));
        }
    }

    /**
     * 将当前用户有权访问的远程文件以流式方式下载。
     *
     * @param userId 认证过滤器注入的用户ID
     * @param path 远程文件路径
     * @param accountId 远程账号ID
     * @return 流式文件响应
     */
    @GetMapping("/api/app/v1/files/download")
    public ResponseEntity<StreamingResponseBody> downloadFileStream(
            @RequestAttribute("userId") Long userId,
            @RequestParam("path") String path,
            @RequestParam("accountId") Long accountId) {
        String decodedPath = decode(path);
        RemoteMediaStream stream = cloudFileService.openDownloadStream(userId, accountId, decodedPath);
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = stream.body()) {
                inputStream.transferTo(outputStream);
            }
        };
        String filename = decodedPath.substring(decodedPath.lastIndexOf('/') + 1);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(stream.statusCode())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8));
        stream.contentType().ifPresent(value -> response.header(HttpHeaders.CONTENT_TYPE, value));
        stream.contentLength().ifPresent(value -> response.header(HttpHeaders.CONTENT_LENGTH, value));
        stream.etag().ifPresent(value -> response.header(HttpHeaders.ETAG, value));
        stream.lastModified().ifPresent(value -> response.header(HttpHeaders.LAST_MODIFIED, value));
        return response.body(body);
    }

    @PostMapping("/api/cloud/file")
    public ResponseEntity<Result<FileOperationResponse>> uploadFile(
            @RequestHeader("Authorization") String auth,
            @RequestParam("file") MultipartFile file,
            @RequestParam("path") String dirPath,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        String targetFilename = (filename != null && !filename.isBlank()) ? filename : file.getOriginalFilename();
        try {
            FileOperationResponse resp = cloudFileService.uploadFileStream(
                    userId, accountId, decode(dirPath), targetFilename, file.getInputStream(), file.getSize());
            return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), resp));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Result.error("50001", e.getMessage()));
        }
    }

    @PostMapping("/api/cloud/dir")
    public ResponseEntity<Result<Void>> createDir(
            @RequestHeader("Authorization") String auth,
            @RequestBody FileOperationRequest request,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        cloudFileService.createDirectory(userId, accountId, decode(request.getPath()));
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    @PostMapping("/api/cloud/rename")
    public ResponseEntity<Result<Void>> rename(
            @RequestHeader("Authorization") String auth,
            @RequestBody FileOperationRequest request,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        cloudFileService.rename(userId, accountId, decode(request.getPath()), request.getNewName());
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    @PostMapping("/api/cloud/move")
    public ResponseEntity<Result<Void>> move(
            @RequestHeader("Authorization") String auth,
            @RequestBody FileOperationRequest request,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        cloudFileService.move(userId, accountId, decode(request.getFrom()), decode(request.getTo()));
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    @PostMapping("/api/cloud/copy")
    public ResponseEntity<Result<Void>> copy(
            @RequestHeader("Authorization") String auth,
            @RequestBody FileOperationRequest request,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        cloudFileService.copy(userId, accountId, decode(request.getFrom()), decode(request.getTo()));
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    @DeleteMapping("/api/cloud/file")
    public ResponseEntity<Result<Void>> delete(
            @RequestHeader("Authorization") String auth,
            @RequestParam("path") String path,
            @RequestParam(value = "recursive", defaultValue = "false") boolean recursive,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        cloudFileService.delete(userId, accountId, decode(path), recursive);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    @PutMapping("/api/cloud/text-file")
    public ResponseEntity<Result<Void>> saveTextFile(
            @RequestHeader("Authorization") String auth,
            @RequestParam("path") String path,
            @RequestBody String content,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        cloudFileService.saveTextFile(userId, accountId, decode(path), content);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    @GetMapping("/api/cloud/alist/raw")
    public ResponseEntity<Result<Map<String, String>>> getAlistRawUrl(
            @RequestHeader("Authorization") String auth,
            @RequestParam("path") String path,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        Long userId = resolveUserId(auth);
        String rawUrl = cloudFileService.alistRawUrl(userId, accountId, decode(path));
        return ResponseEntity.ok(Result.success(Map.of("rawUrl", rawUrl)));
    }

    /**
     * 将当前用户有权访问的 WebDAV 媒体以流式方式转发给客户端。
     *
     * @param auth Authorization 请求头
     * @param path 远程文件路径
     * @param accountId 远程账号ID
     * @param rangeHeader 单段字节范围
     * @return 流式媒体响应
     */
    @GetMapping("/api/app/v1/media/stream")
    public ResponseEntity<StreamingResponseBody> streamMedia(
            @RequestHeader("Authorization") String auth,
            @RequestParam("path") String path,
            @RequestParam("accountId") Long accountId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        Long userId = resolveUserId(auth);
        RemoteMediaStream stream = cloudFileService.openMediaStream(userId, accountId, decode(path), rangeHeader);
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = stream.body()) {
                inputStream.transferTo(outputStream);
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(stream.statusCode())
                .header(HttpHeaders.ACCEPT_RANGES, stream.acceptRanges().orElse("bytes"))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store");
        stream.contentType().ifPresent(value -> response.header(HttpHeaders.CONTENT_TYPE, value));
        stream.contentLength().ifPresent(value -> response.header(HttpHeaders.CONTENT_LENGTH, value));
        stream.contentRange().ifPresent(value -> response.header(HttpHeaders.CONTENT_RANGE, value));
        stream.etag().ifPresent(value -> response.header(HttpHeaders.ETAG, value));
        stream.lastModified().ifPresent(value -> response.header(HttpHeaders.LAST_MODIFIED, value));
        return response.body(body);
    }

    private Long resolveUserId(String auth) {
        String token = extractToken(auth);
        return jwtUtils.getUserIdFromToken(token);
    }

    private String extractToken(String auth) {
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        return auth;
    }

    private String decode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }

    private MediaType detectMediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.valueOf("image/webp");
        if (lower.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        if (lower.endsWith(".bmp")) return MediaType.valueOf("image/bmp");
        if (lower.endsWith(".ico")) return MediaType.valueOf("image/x-icon");
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return MediaType.TEXT_PLAIN;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return MediaType.TEXT_HTML;
        if (lower.endsWith(".css")) return MediaType.valueOf("text/css");
        if (lower.endsWith(".js")) return MediaType.valueOf("application/javascript");
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (lower.endsWith(".xml")) return MediaType.APPLICATION_XML;
        if (lower.endsWith(".zip")) return MediaType.valueOf("application/zip");
        if (lower.endsWith(".mp3")) return MediaType.valueOf("audio/mpeg");
        if (lower.endsWith(".mp4")) return MediaType.valueOf("video/mp4");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
