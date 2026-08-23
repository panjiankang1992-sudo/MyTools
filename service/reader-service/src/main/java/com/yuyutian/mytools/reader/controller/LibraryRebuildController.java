package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateLibraryRebuildRequest;
import com.yuyutian.mytools.reader.model.LibraryIndexEntryView;
import com.yuyutian.mytools.reader.model.LibraryRebuildBatchResult;
import com.yuyutian.mytools.reader.model.LibraryRebuildView;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.reader.service.LibraryRebuildService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 书库索引重建和已发布索引接口。
 */
@Validated
@RestController
public class LibraryRebuildController {

    private final LibraryRebuildService service;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建书库索引重建控制器。
     */
    public LibraryRebuildController(LibraryRebuildService service, InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /**
     * 创建书库索引重建任务。
     */
    @PostMapping("/api/internal/v1/library-rebuilds")
    public ResponseEntity<LibraryRebuildView> create(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateLibraryRebuildRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.create(request));
    }

    /**
     * 查询书库索引重建任务。
     */
    @GetMapping("/api/internal/v1/library-rebuilds/{id}")
    public LibraryRebuildView get(@RequestHeader(name = "Authorization", required = false) String authorization,
                                  @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.get(id);
    }

    /**
     * 写入一个书库索引暂存批次。
     */
    @PostMapping("/api/internal/v1/library-rebuilds/{id}/batches")
    public LibraryRebuildBatchResult batch(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.rebuildBatch(id);
    }

    /**
     * 原子发布完整的书库索引 generation。
     */
    @PostMapping("/api/internal/v1/library-rebuilds/{id}/publish")
    public LibraryRebuildView publish(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.publish(id);
    }

    /**
     * 设置书库索引重建异常终态。
     */
    @PostMapping("/api/internal/v1/library-rebuilds/{id}/finish")
    public LibraryRebuildView finish(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @RequestParam String status,
            @RequestParam(required = false) String errorCode) {
        authorizer.requireAuthorized(authorization);
        return service.finish(id, status, errorCode);
    }

    /**
     * 查询当前已发布的可再生书库索引。
     */
    @GetMapping("/api/v1/library-index")
    public List<LibraryIndexEntryView> activeIndex(@RequestParam @Positive long ownerId) {
        return service.activeIndex(ownerId);
    }
}
