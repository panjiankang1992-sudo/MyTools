package com.yuyutian.mytools.messaging.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 历史入站消息迁移批次。
 *
 * @param migrationKey 迁移幂等键
 * @param dryRun 是否仅校验
 * @param items 消息集合
 */
public record LegacyInboundMigrationBatch(
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String migrationKey,
        boolean dryRun,
        @NotEmpty @Valid @Size(max = 200) List<LegacyInboundMessageItem> items) {

    /**
     * 返回不可变迁移集合。
     *
     * @return 迁移消息
     */
    @Override
    public List<LegacyInboundMessageItem> items() {
        return List.copyOf(items);
    }
}
