package com.yuyutian.mytools.messaging.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 历史发件记录迁移批次。 */
public record LegacyOutboundMigrationBatch(
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String migrationKey,
        boolean dryRun,
        @NotEmpty @Valid @Size(max = 200) List<LegacyOutboundMessageItem> items) {
    /** 返回不可变迁移集合。 */
    @Override public List<LegacyOutboundMessageItem> items() { return List.copyOf(items); }
}
