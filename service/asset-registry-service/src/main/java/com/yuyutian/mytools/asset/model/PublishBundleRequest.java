package com.yuyutian.mytools.asset.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 原子发布不可变资产资源包的请求。
 *
 * @param ownerId 所有者
 * @param idempotencyKey 幂等键
 * @param name 资源包名称
 * @param description 描述
 * @param items 固定资产清单
 */
public record PublishBundleRequest(@PositiveOrZero long ownerId,
                                   @NotBlank @Size(max = 255) String idempotencyKey,
                                   @NotBlank @Size(max = 128)
                                   @Pattern(regexp = "^[A-Za-z0-9_]+$") String name,
                                   @Size(max = 1024) String description,
                                   @NotEmpty @Size(max = 1000) List<@Valid Item> items) {

    /**
     * 返回不可变资源项集合。
     *
     * @return 资源项
     */
    @Override
    public List<Item> items() {
        return items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 资源包中的固定资产快照。
     *
     * @param assetId 资产标识
     * @param expectedAssetVersion 资产预期版本
     * @param logicalPath 包内逻辑路径
     * @param role 资源角色
     */
    public record Item(@NotNull UUID assetId,
                       @Positive long expectedAssetVersion,
                       @NotBlank @Size(max = 1024) String logicalPath,
                       @NotBlank @Size(max = 64)
                       @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String role) {
    }
}
