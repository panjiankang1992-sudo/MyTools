package com.yuyutian.mytools.drive.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务端统一网盘账号，不向 App 暴露远端实现信息。
 */
@Data
public class DriveAccount {
    private Long id;
    private Long userId;
    private String displayName;
    private String remoteKey;
    private Boolean readOnly;
    private Boolean enabled;
    private String status;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
