package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CacheMaintenanceBatchResult;
import com.yuyutian.mytools.reader.model.CacheMaintenanceView;
import com.yuyutian.mytools.reader.model.CreateCacheMaintenanceRequest;
import com.yuyutian.mytools.reader.service.CacheMaintenanceService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 章节缓存维护编排与执行接口。
 */
@RestController
public class CacheMaintenanceController {

    private final CacheMaintenanceService service;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建章节缓存维护控制器。
     */
    public CacheMaintenanceController(CacheMaintenanceService service, InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /**
     * 创建章节缓存维护任务。
     */
    @PostMapping("/api/internal/v1/cache-maintenance")
    public ResponseEntity<CacheMaintenanceView> create(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateCacheMaintenanceRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.create(request));
    }

    /**
     * 查询章节缓存维护任务。
     */
    @GetMapping("/api/internal/v1/cache-maintenance/{id}")
    public CacheMaintenanceView get(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.get(id);
    }

    /**
     * 删除一个受限章节缓存批次。
     */
    @PostMapping("/api/internal/v1/cache-maintenance/{id}/batches")
    public CacheMaintenanceBatchResult deleteBatch(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.deleteBatch(id);
    }

    /**
     * 设置章节缓存维护终态。
     */
    @PostMapping("/api/internal/v1/cache-maintenance/{id}/finish")
    public CacheMaintenanceView finish(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @RequestParam String status,
            @RequestParam(required = false) String errorCode) {
        authorizer.requireAuthorized(authorization);
        return service.finish(id, status, errorCode);
    }
}
