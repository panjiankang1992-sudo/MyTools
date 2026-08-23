package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 对外隐藏密钥引用的 Provider 视图。
 *
 * @param id 标识
 * @param name 名称
 * @param providerType 类型
 * @param enabled 是否启用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ProviderView(UUID id, String name, String providerType, boolean enabled,
                           Instant createdAt, Instant updatedAt) {
    /**
     * 从持久化模型创建安全视图。
     *
     * @param provider Provider
     * @return 安全视图
     */
    public static ProviderView from(StorageProvider provider) {
        return new ProviderView(provider.id(), provider.name(), provider.providerType(), provider.enabled(),
                provider.createdAt(), provider.updatedAt());
    }
}
