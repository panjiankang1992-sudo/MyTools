package com.yuyutian.mytools.drive.controller;

import com.yuyutian.mytools.drive.config.DriveConfiguration.StorageMigrationToken;
import com.yuyutian.mytools.drive.model.DriveModels.StorageMigrationPage;
import com.yuyutian.mytools.drive.repository.DriveRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 仅暴露 Secret 引用的 Storage Provider 迁移接口。
 */
@RestController
@RequestMapping("/internal/v1/drive/migration")
public class DriveStorageMigrationController {
    private final DriveRepository repository;
    private final StorageMigrationToken token;

    /** 创建迁移控制器。 @param repository 仓储 @param token 独立迁移令牌 */
    public DriveStorageMigrationController(DriveRepository repository,StorageMigrationToken token) {
        this.repository=repository; this.token=token;
    }

    /** 查询安全账户分页。 @param authorization 授权头 @param afterId 游标 @param limit 数量 @return 分页 */
    @GetMapping("/storage-accounts")
    public StorageMigrationPage accounts(@RequestHeader("Authorization") String authorization,
        @RequestParam(required=false) UUID afterId,@RequestParam(defaultValue="100") int limit) {
        authorize(authorization);
        if(limit<1||limit>500) throw new IllegalArgumentException("drive migration page size is invalid");
        return repository.listStorageMigrationAccounts(afterId,limit);
    }

    private void authorize(String authorization) {
        String expected=token.value(); String supplied=authorization.startsWith("Bearer ")?authorization.substring(7):"";
        if(expected.isBlank()||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8))) throw new SecurityException("drive migration authorization failed");
    }
}
