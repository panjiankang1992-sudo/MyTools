package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建远端存储 Provider 请求。
 *
 * @param name Provider 名称
 * @param providerType Provider 类型
 * @param remoteKey 服务端 rclone remote 键
 * @param secretRef 密钥系统引用
 * @param enabled 是否启用
 */
public record CreateProviderRequest(
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String name,
        @NotBlank @Pattern(regexp = "^RCLONE$") String providerType,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String remoteKey,
        @NotBlank @Size(max = 512) @Pattern(regexp = "^(secret|vault|env)://[A-Za-z0-9._/:-]+$") String secretRef,
        boolean enabled) {
}
