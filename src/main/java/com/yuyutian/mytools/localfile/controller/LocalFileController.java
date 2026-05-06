package com.yuyutian.mytools.localfile.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地文件管理 Controller。
 *
 * @author mytools
 * @since 2026-05-04
 */
@RestController
@RequestMapping("/api/localfiles")
@RequiredArgsConstructor
public class LocalFileController {

    private final LocalFileService localFileService;

    /**
     * 分页获取文件列表。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Map<String, Object>>> getFilePage(
            @RequestParam Long page,
            @RequestParam Long pageSize) {
        List<LocalFile> files = localFileService.getFilePage(page, pageSize);
        long total = localFileService.countFiles();

        Map<String, Object> data = new HashMap<>();
        data.put("list", files);
        data.put("total", total);

        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 获取文件详情。
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<LocalFile>> getFileById(@PathVariable Long id) {
        LocalFile file = localFileService.getFileById(id);
        if (file == null) {
            return ResponseEntity.ok(Result.error("SYS_001", "文件不存在"));
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

    /**
     * 手动触发文件打标签。
     */
    @PostMapping("/{id}/tag")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<List<FileTag>>> triggerTagging(@PathVariable Long id) {
        try {
            List<FileTag> tags = localFileService.triggerTagging(id);
            return ResponseEntity.ok(Result.success("打标签成功", tags));
        } catch (Exception e) {
            return ResponseEntity.ok(Result.error("SYS_001", "打标签失败: " + e.getMessage()));
        }
    }
}
