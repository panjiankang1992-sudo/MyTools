package com.yuyutian.mytools.drive.migration;

import com.yuyutian.mytools.drive.mapper.DriveAccountMapper;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** 仅导出账户元数据和 Secret 引用的旧 Drive 迁移接口。 */
@RestController
@RequestMapping("/internal/v1/migration/drive-accounts")
public class LegacyDriveAccountExportController {
    private final DriveAccountMapper driveMapper;
    private final WebdavAccountMapper webdavMapper;
    private final String internalToken;

    /** 创建迁移接口。 @param driveMapper Drive Mapper @param webdavMapper WebDAV Mapper @param internalToken 内部令牌 */
    public LegacyDriveAccountExportController(DriveAccountMapper driveMapper,WebdavAccountMapper webdavMapper,
        @Value("${migration.drive-accounts.internal-token:}") String internalToken) {
        this.driveMapper=driveMapper; this.webdavMapper=webdavMapper; this.internalToken=internalToken;
    }

    /** 分页导出非敏感账户元数据。 @param authorization 授权头 @param source 来源 @param afterId 游标 @param limit 大小 @return 页面 */
    @GetMapping
    public ExportPage export(@RequestHeader("Authorization") String authorization,@RequestParam String source,
        @RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="100") int limit) {
        authorize(authorization);
        if(afterId<0||limit<1||limit>500) throw new IllegalArgumentException("migration page is invalid");
        List<ExportAccount> accounts=switch(source) {
            case "DRIVE" -> driveMapper.selectMigrationBatch(afterId,limit).stream().map(account -> new ExportAccount(
                account.getId(),account.getUserId(),"drive:"+account.getId(),account.getDisplayName(),"RCLONE",
                "secret://mytools/rclone/"+account.getId(),account.getRemoteKey(),Boolean.TRUE.equals(account.getReadOnly()),
                Boolean.TRUE.equals(account.getEnabled()))).toList();
            case "WEBDAV" -> webdavMapper.selectMigrationBatch(afterId,limit).stream().map(account -> new ExportAccount(
                account.getId(),account.getUserId(),"webdav:"+account.getId(),account.getName(),provider(account.getType()),
                "secret://mytools/webdav/"+account.getId(),"legacy_webdav_"+account.getId(),true,false)).toList();
            default -> throw new IllegalArgumentException("migration source is invalid");
        };
        long next=accounts.isEmpty()?afterId:accounts.getLast().legacyId();
        return new ExportPage(accounts,next,accounts.size()<limit);
    }
    private String provider(String value) {
        String normalized=value==null?"WEBDAV":value.trim().toUpperCase().replaceAll("[^A-Z0-9]+","_");
        return normalized.isBlank()?"WEBDAV":normalized.substring(0,Math.min(64,normalized.length()));
    }
    private void authorize(String authorization) {
        byte[] expected=("Bearer "+internalToken).getBytes(StandardCharsets.UTF_8);
        if(internalToken.isBlank()||!MessageDigest.isEqual(expected,authorization.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("drive migration authorization failed");
    }
    /** 迁移账户元数据。 */
    public record ExportAccount(long legacyId,long ownerId,String externalAccountId,String displayName,
        String providerType,String providerSecretRef,String remoteKey,boolean readOnly,boolean enabled) { }
    /** 迁移分页。 */
    public record ExportPage(List<ExportAccount> accounts,long nextAfterId,boolean complete) { }
}
