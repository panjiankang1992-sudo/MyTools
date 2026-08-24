package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.LegacyOutboundMigrationBatch;
import com.yuyutian.mytools.messaging.model.LegacyOutboundMigrationResult;
import com.yuyutian.mytools.messaging.model.LegacyOutboundReconciliation;
import com.yuyutian.mytools.messaging.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.messaging.service.OutboundHistoryMigrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受保护的历史发件归档迁移接口。 */
@RestController
@RequestMapping("/internal/v1/migrations/legacy-outbound")
public class OutboundHistoryMigrationController {
    private final InternalRequestAuthorizer authorizer;
    private final OutboundHistoryMigrationService migrationService;

    /** 创建历史发件迁移控制器。 */
    public OutboundHistoryMigrationController(InternalRequestAuthorizer authorizer,
                                              OutboundHistoryMigrationService migrationService) {
        this.authorizer = authorizer;
        this.migrationService = migrationService;
    }

    /** 校验或导入历史发件批次。 */
    @PostMapping("/batches")
    public LegacyOutboundMigrationResult migrate(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody LegacyOutboundMigrationBatch batch) {
        authorizer.requireAuthorized(authorization);
        return migrationService.migrate(batch);
    }

    /** 查询历史发件迁移对账证据。 */
    @GetMapping("/{migrationKey}/reconciliation")
    public LegacyOutboundReconciliation reconcile(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable String migrationKey) {
        authorizer.requireAuthorized(authorization);
        return migrationService.reconcile(migrationKey);
    }
}
