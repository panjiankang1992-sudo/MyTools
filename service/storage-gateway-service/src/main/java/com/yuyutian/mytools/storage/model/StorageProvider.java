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
 * @param secretRef 密钥引用
 * @param enabled 是否启用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record StorageProvider(UUID id, String name, String providerType, String remoteKey,
                              String secretRef, boolean enabled, Instant createdAt, Instant updatedAt) {
}
