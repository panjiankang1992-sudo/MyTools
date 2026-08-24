package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.ChapterCacheView;
import com.yuyutian.mytools.reader.model.ChapterPrefetchView;
import com.yuyutian.mytools.reader.model.CreateChapterPrefetchRequest;
import com.yuyutian.mytools.reader.service.ChapterPrefetchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 章节预取编排与缓存查询接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ChapterPrefetchController {

    private final ChapterPrefetchService service;

    /**
     * 创建章节预取控制器。
     *
     * @param service 章节预取服务
     */
    public ChapterPrefetchController(ChapterPrefetchService service) {
        this.service = service;
    }

    /**
     * 创建异步章节预取任务。
     *
     * @param request 创建请求
     * @return 已受理任务
     */
    @PostMapping("/chapter-prefetches")
    public ResponseEntity<ChapterPrefetchView> create(@Valid @RequestBody CreateChapterPrefetchRequest request) {
        return ResponseEntity.accepted().body(service.create(request));
    }

    /**
     * 查询章节预取任务。
     *
     * @param id 请求标识
     * @param ownerId 可选所有者标识
     * @return 任务视图
     */
    @GetMapping("/chapter-prefetches/{id}")
    public ChapterPrefetchView get(@PathVariable UUID id, @RequestParam(required = false) Long ownerId) {
        return ownerId == null ? service.get(id) : service.get(id, ownerId);
    }

    /**
     * 取消章节预取任务。
     *
     * @param id 请求标识
     * @param ownerId 可选所有者标识
     * @return 任务视图
     */
    @PostMapping("/chapter-prefetches/{id}/cancel")
    public ChapterPrefetchView cancel(@PathVariable UUID id, @RequestParam(required = false) Long ownerId) {
        return ownerId == null ? service.cancel(id) : service.cancel(id, ownerId);
    }

    /**
     * 查询未过期章节缓存。
     *
     * @param ownerId 所有者标识
     * @param sourceId 书源标识
     * @param bookUrl 图书地址
     * @param chapterUrl 章节地址
     * @return 缓存视图
     */
    @GetMapping("/chapter-cache")
    public ChapterCacheView cached(@RequestParam @NotNull Long ownerId,
                                   @RequestParam @NotNull UUID sourceId,
                                   @RequestParam @NotBlank @Size(max = 4096) String bookUrl,
                                   @RequestParam @NotBlank @Size(max = 4096) String chapterUrl) {
        return service.cached(ownerId, sourceId, bookUrl, chapterUrl);
    }
}
