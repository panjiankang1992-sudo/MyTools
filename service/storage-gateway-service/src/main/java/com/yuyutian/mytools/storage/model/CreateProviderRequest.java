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
 * @param endpointUri 原生 Provider 服务端地址
 * @param regionName S3 签名区域
 * @param secretRef 密钥系统引用
 * @param enabled 是否启用
 */
public record CreateProviderRequest(
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String name,
        @NotBlank @Pattern(regexp = "^(RCLONE|WEBDAV|S3)$") String providerType,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String remoteKey,
        @Size(max = 2048) String endpointUri,
        @Size(max = 64) String regionName,
        @NotBlank @Size(max = 512) @Pattern(regexp = "^(secret|vault|env)://[A-Za-z0-9._/:-]+$") String secretRef,
        boolean enabled) {
    /**
     * 创建兼容既有 rclone 注册契约的请求。
     *
     * @param name Provider 名称
     * @param providerType Provider 类型
     * @param remoteKey rclone remote 键
     * @param secretRef 密钥引用
     * @param enabled 是否启用
     */
    public CreateProviderRequest(String name, String providerType, String remoteKey,
                                 String secretRef, boolean enabled) {
        this(name, providerType, remoteKey, null, null, secretRef, enabled);
    }

    /**
     * 创建 WebDAV 兼容请求。
     *
     * @param name Provider 名称
     * @param providerType Provider 类型
     * @param remoteKey Provider 内部键
     * @param endpointUri 原生服务端地址
     * @param secretRef 密钥引用
     * @param enabled 是否启用
     */
    public CreateProviderRequest(String name, String providerType, String remoteKey, String endpointUri,
                                 String secretRef, boolean enabled) {
        this(name, providerType, remoteKey, endpointUri, null, secretRef, enabled);
    }
}
