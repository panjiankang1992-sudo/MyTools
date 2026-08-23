package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.LegacyReaderMigrationBatch;
import com.yuyutian.mytools.reader.model.LegacyReaderMigrationResult;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.reader.service.LegacyReaderMigrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
