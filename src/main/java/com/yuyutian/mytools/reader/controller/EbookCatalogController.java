package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.EbookCatalogItem;
import com.yuyutian.mytools.reader.model.EbookCatalogPage;
import com.yuyutian.mytools.reader.model.EbookIndexResult;
import com.yuyutian.mytools.reader.model.EbookCover;
import com.yuyutian.mytools.reader.service.EbookCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;

/**
 * MyTools电子书领域目录接口。
 */
@RestController
@RequestMapping("/api/ebooks")
@RequiredArgsConstructor
public class EbookCatalogController {
    private final EbookCatalogService ebookCatalogService;

    /**
     * 分页搜索电子书目录。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<EbookCatalogPage>> list(
            @RequestParam(required = false) Long directoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean excludeAdult,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "40") long pageSize) {
        return ResponseEntity.ok(Result.success(
                ebookCatalogService.list(directoryId, keyword, excludeAdult, page, pageSize)));
    }

    /**
     * 获取单本电子书详情。
     */
    @GetMapping("/{fileId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<EbookCatalogItem>> detail(
            @PathVariable Long fileId,
            @RequestParam(required = false) Long directoryId,
            @RequestParam(defaultValue = "false") boolean excludeAdult) {
        return ResponseEntity.ok(Result.success(ebookCatalogService.detail(directoryId, fileId, excludeAdult)));
    }

    /**
     * 获取单本电子书经过安全提取的真实封面。
     */
    @GetMapping("/{fileId}/cover")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> cover(
            @PathVariable Long fileId,
            @RequestParam(required = false) Long directoryId,
            @RequestParam(defaultValue = "false") boolean excludeAdult) {
        EbookCover cover = ebookCatalogService.cover(directoryId, fileId, excludeAdult);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(cover.mediaType()))
                .contentLength(cover.content().length)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(cover.content());
    }

    /**
     * 管理员触发一批电子书增量索引。
     */
    @PostMapping("/index/scan")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<EbookIndexResult>> index(
            @RequestParam(required = false) Long directoryId,
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(Result.success(ebookCatalogService.index(directoryId, limit)));
    }
}
