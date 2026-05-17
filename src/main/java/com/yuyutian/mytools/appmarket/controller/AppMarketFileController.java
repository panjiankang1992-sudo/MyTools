package com.yuyutian.mytools.appmarket.controller;

import com.yuyutian.mytools.appmarket.entity.AppFile;
import com.yuyutian.mytools.appmarket.service.AppMarketService;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 应用市场文件 Controller。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AppMarketFileController {

    private final AppMarketService appMarketService;
    private final JwtUtils jwtUtils;

    /**
     * 上传应用文件或缩略图。
     */
    @PostMapping("/api/market/apps/{appId}/files")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<AppFile>> uploadFile(
            @PathVariable String appId,
            @RequestParam String fileType,
            @RequestPart("file") MultipartFile file,
            @RequestHeader("Authorization") String authHeader) throws IOException {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        AppFile appFile = appMarketService.uploadFile(appId, fileType, file, userId);
        return ResponseEntity.ok(Result.success("上传成功", appFile));
    }

    /**
     * 下载文件。
     */
    @GetMapping("/api/market/files/{fileId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) {
        AppFile appFile = appMarketService.getFile(fileId);
        String filePath = appFile.getFilePath();

        try {
            Resource resource = new FileSystemResource(filePath);
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            String filename = appFile.getFileName();
            String contentType = Files.probeContentType(Paths.get(filePath));
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (IOException e) {
            log.error("下载文件失败: fileId={}, error={}", fileId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除文件。
     */
    @DeleteMapping("/api/market/apps/{appId}/files/{fileId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> deleteFile(
            @PathVariable String appId,
            @PathVariable String fileId,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        appMarketService.deleteFile(appId, fileId, userId);
        return ResponseEntity.ok(Result.success("删除成功", null));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }
}
