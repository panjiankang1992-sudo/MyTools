package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.ChapterCacheBatchRequest;
import com.yuyutian.mytools.reader.service.ChapterPrefetchService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 执行器写入章节缓存的内部接口。
 */
@RestController
@RequestMapping("/api/internal/v1/chapter-prefetches")
public class ChapterCacheInternalController {

    private final ChapterPrefetchService service;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建章节缓存内部控制器。
     */
    public ChapterCacheInternalController(ChapterPrefetchService service, InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /**
     * 保存一个受限章节批次。
     */
    @PostMapping("/{id}/chapters")
    public Map<String, Integer> save(@RequestHeader(name = "Authorization", required = false) String authorization,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody ChapterCacheBatchRequest request) {
        authorizer.requireAuthorized(authorization);
        return Map.of("saved", service.saveBatch(id, request));
    }
}
