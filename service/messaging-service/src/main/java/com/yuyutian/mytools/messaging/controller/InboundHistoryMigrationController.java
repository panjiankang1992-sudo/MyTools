package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.LegacyInboundMigrationBatch;
import com.yuyutian.mytools.messaging.model.LegacyInboundMigrationResult;
import com.yuyutian.mytools.messaging.service.InboundHistoryMigrationService;
import com.yuyutian.mytools.messaging.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受保护的历史入站消息迁移接口。
 */
@RestController
@RequestMapping("/internal/v1/migrations/legacy-inbound")
public class InboundHistoryMigrationController {
    private final InternalRequestAuthorizer authorizer;
    private final InboundHistoryMigrationService migrationService;

    /**
     * 创建历史迁移控制器。
     *
     * @param authorizer 内部鉴权器
     * @param migrationService 历史迁移服务
     */
    public InboundHistoryMigrationController(InternalRequestAuthorizer authorizer,
                                             InboundHistoryMigrationService migrationService) {
        this.authorizer = authorizer;
        this.migrationService = migrationService;
    }

    /**
     * 校验或导入一个历史消息批次。
     *
     * @param authorization 内部授权头
     * @param batch 迁移批次
     * @return 审计结果
     */
    @PostMapping("/batches")
    public LegacyInboundMigrationResult migrate(
                                                 @RequestHeader(name = "Authorization", required = false)
                                                 String authorization,
                                                 @Valid @RequestBody LegacyInboundMigrationBatch batch) {
        authorizer.requireAuthorized(authorization);
        return migrationService.migrate(batch);
    }
}
