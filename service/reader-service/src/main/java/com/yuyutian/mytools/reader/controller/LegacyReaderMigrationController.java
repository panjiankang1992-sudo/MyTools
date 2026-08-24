package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.LegacyReaderMigrationBatch;
import com.yuyutian.mytools.reader.model.LegacyReaderMigrationEvidence;
import com.yuyutian.mytools.reader.model.LegacyReaderMigrationResult;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.reader.service.LegacyReaderMigrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旧 Reader 用户数据迁移内部接口。
 */
@RestController
@RequestMapping("/api/internal/v1/migrations/legacy-reader")
public class LegacyReaderMigrationController {

    private final InternalRequestAuthorizer authorizer;
    private final LegacyReaderMigrationService service;

    /**
     * 创建旧 Reader 用户数据迁移控制器。
     */
    public LegacyReaderMigrationController(InternalRequestAuthorizer authorizer,
                                           LegacyReaderMigrationService service) {
        this.authorizer = authorizer;
        this.service = service;
    }

    /**
     * 校验或导入一个迁移批次。
     */
    @PostMapping("/batches")
    public LegacyReaderMigrationResult migrate(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody LegacyReaderMigrationBatch batch) {
        authorizer.requireAuthorized(authorization);
        return service.migrate(batch);
    }

    /**
     * 返回一个迁移实例已经提交的目标集合证据。
     */
    @GetMapping("/evidence")
    public LegacyReaderMigrationEvidence evidence(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam String migrationKey) {
        authorizer.requireAuthorized(authorization);
        return service.evidence(migrationKey);
    }
}
