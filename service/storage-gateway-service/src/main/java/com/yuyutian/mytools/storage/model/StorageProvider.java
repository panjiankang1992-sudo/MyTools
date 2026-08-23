package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 不包含明文凭据的远端存储 Provider。
 *
 * @param id 标识
 * @param name 名称
 * @param providerType 类型
 * @param remoteKey rclone remote 键
 * @param endpointUri 原生 Provider 服务端地址
 * @param regionName S3 签名区域
 * @param secretRef 密钥引用
 * @param enabled 是否启用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record StorageProvider(UUID id, String name, String providerType, String remoteKey, String endpointUri,
                              String regionName,
                              String secretRef, boolean enabled, Instant createdAt, Instant updatedAt) {
    /**
     * 创建兼容既有 rclone Provider 的持久化模型。
     *
     * @param id 标识
     * @param name 名称
     * @param providerType 类型
     * @param remoteKey rclone remote 键
     * @param secretRef 密钥引用
     * @param enabled 是否启用
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public StorageProvider(UUID id, String name, String providerType, String remoteKey,
                           String secretRef, boolean enabled, Instant createdAt, Instant updatedAt) {
        this(id, name, providerType, remoteKey, null, null, secretRef, enabled, createdAt, updatedAt);
    }

    /**
     * 创建兼容 WebDAV 的持久化模型。
     *
     * @param id 标识
     * @param name 名称
     * @param providerType 类型
     * @param remoteKey Provider 内部键
     * @param endpointUri 服务端地址
     * @param secretRef 密钥引用
     * @param enabled 是否启用
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public StorageProvider(UUID id, String name, String providerType, String remoteKey, String endpointUri,
                           String secretRef, boolean enabled, Instant createdAt, Instant updatedAt) {
        this(id, name, providerType, remoteKey, endpointUri, null, secretRef, enabled, createdAt, updatedAt);
    }
}
