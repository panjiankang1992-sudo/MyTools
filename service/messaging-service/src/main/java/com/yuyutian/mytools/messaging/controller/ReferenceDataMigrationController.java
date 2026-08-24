package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.LegacyReferenceDataBatch;
import com.yuyutian.mytools.messaging.model.LegacyReferenceDataReconciliation;
import com.yuyutian.mytools.messaging.model.LegacyReferenceDataResult;
import com.yuyutian.mytools.messaging.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.messaging.service.ReferenceDataMigrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受保护的 MsgService 参考数据迁移接口。 */
@RestController
@RequestMapping("/internal/v1/migrations/msgservice-reference-data")
public class ReferenceDataMigrationController {
    private final InternalRequestAuthorizer authorizer;
    private final ReferenceDataMigrationService migrationService;

    /** 创建参考数据迁移控制器。 */
    public ReferenceDataMigrationController(InternalRequestAuthorizer authorizer,
                                            ReferenceDataMigrationService migrationService) {
        this.authorizer = authorizer;
        this.migrationService = migrationService;
    }

    /** 校验或导入模板与收件人批次。 */
    @PostMapping("/batches")
    public LegacyReferenceDataResult migrate(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody LegacyReferenceDataBatch batch) {
        authorizer.requireAuthorized(authorization);
        return migrationService.migrate(batch);
    }

    /** 查询指定迁移键的目标侧证据。 */
    @GetMapping("/{migrationKey}/reconciliation")
    public LegacyReferenceDataReconciliation reconcile(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable String migrationKey) {
        authorizer.requireAuthorized(authorization);
        return migrationService.reconcile(migrationKey);
    }
}
