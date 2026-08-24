package com.yuyutian.mytools.asset.controller;

import com.yuyutian.mytools.asset.model.LegacyAssetMappingBatch;
import com.yuyutian.mytools.asset.model.LegacyAssetMappingResult;
import com.yuyutian.mytools.asset.model.LegacyAssetMappingLookupRequest;
import com.yuyutian.mytools.asset.model.LegacyAssetMappingLookupResult;
import com.yuyutian.mytools.asset.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.asset.service.LegacyAssetMappingMigrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受保护的旧资产身份映射迁移接口。
 */
@RestController
@RequestMapping("/internal/v1/assets/migrations/legacy-mappings")
public class LegacyAssetMappingMigrationController {
    private final InternalRequestAuthorizer authorizer;
    private final LegacyAssetMappingMigrationService migrationService;

    /**
     * 创建旧资产映射迁移控制器。
     */
    public LegacyAssetMappingMigrationController(InternalRequestAuthorizer authorizer,
                                                 LegacyAssetMappingMigrationService migrationService) {
        this.authorizer = authorizer;
        this.migrationService = migrationService;
    }

    /**
     * 预演或迁移一个有界旧资产批次。
     */
    @PostMapping("/batches")
    public LegacyAssetMappingResult migrate(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody LegacyAssetMappingBatch batch) {
        authorizer.requireAuthorized(authorization);
        return migrationService.migrate(batch);
    }

    /**
     * 批量解析不可变旧资产映射。
     *
     * @param authorization 内部访问令牌
     * @param request 旧资产身份集合
     * @return 已解析映射和缺失身份
     */
    @PostMapping("/resolve")
    public LegacyAssetMappingLookupResult resolve(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody LegacyAssetMappingLookupRequest request) {
        authorizer.requireAuthorized(authorization);
        return migrationService.resolve(request);
    }
}
