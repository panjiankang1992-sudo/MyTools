package com.yuyutian.mytools.asset.controller;

import com.yuyutian.mytools.asset.model.AssetView;
import com.yuyutian.mytools.asset.model.AssetBundleView;
import com.yuyutian.mytools.asset.model.AssetReconciliationPage;
import com.yuyutian.mytools.asset.model.InvalidateLocationRequest;
import com.yuyutian.mytools.asset.model.PublishBundleRequest;
import com.yuyutian.mytools.asset.model.RegisterArtifactRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.model.RegisterLocationRequest;
import com.yuyutian.mytools.asset.service.AssetRegistryService;
import com.yuyutian.mytools.asset.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 资产注册服务内部原子接口。
 */
@RestController
@RequestMapping("/internal/v1/assets")
public class AssetController {

    private final AssetRegistryService service;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建资产控制器。
     */
    public AssetController(AssetRegistryService service, InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /**
     * 按内容和来源幂等登记资产。
     */
    @PostMapping
    public ResponseEntity<AssetView> register(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody RegisterAssetRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.ok(service.register(request));
    }

    /**
     * 查询资产及全部关系。
     */
    @GetMapping("/{id}")
    public AssetView get(@RequestHeader(name = "Authorization", required = false) String authorization,
                         @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.get(id);
    }

    /**
     * 登记资产存储位置。
     */
    @PostMapping("/{id}/locations")
    public AssetView registerLocation(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @Valid @RequestBody RegisterLocationRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.registerLocation(id, request);
    }

    /**
     * 登记派生资产关系。
     */
    @PostMapping("/{id}/artifacts")
    public AssetView registerArtifact(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @Valid @RequestBody RegisterArtifactRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.registerArtifact(id, request);
    }

    /**
     * 使用乐观版本显式失效一个存储位置。
     */
    @PostMapping("/{id}/locations/{locationId}/invalidate")
    public AssetView invalidateLocation(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @PathVariable UUID locationId,
            @Valid @RequestBody InvalidateLocationRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.invalidateLocation(id, locationId, request);
    }

    /**
     * 原子发布不可变资源包。
     */
    @PostMapping("/bundles")
    public AssetBundleView publishBundle(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody PublishBundleRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.publishBundle(request);
    }

    /**
     * 查询已发布资源包。
     */
    @GetMapping("/bundles/{bundleId}")
    public AssetBundleView getBundle(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID bundleId) {
        authorizer.requireAuthorized(authorization);
        return service.getBundle(bundleId);
    }

    /**
     * 为异步对账任务提供有界资产摘要页。
     */
    @GetMapping("/reconciliation")
    public AssetReconciliationPage reconciliationPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = "200") int limit) {
        authorizer.requireAuthorized(authorization);
        return service.reconciliationPage(afterId, limit);
    }
}
