package com.yuyutian.mytools.reader.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 旧 Reader 用户数据迁移批次。
 */
public record LegacyReaderMigrationBatch(@NotBlank @Size(max = 255) String migrationKey,
                                         boolean dryRun,
                                         @NotEmpty @Size(max = 500)
                                         List<@Valid LegacyReaderMigrationItem> items) {
}
