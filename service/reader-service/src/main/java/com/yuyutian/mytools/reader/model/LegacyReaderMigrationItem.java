package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 旧 Reader 用户数据迁移条目。
 */
public record LegacyReaderMigrationItem(@NotBlank String entityType,
                                        @NotNull @Positive Long ownerId,
                                        @NotBlank @Size(max = 1000) String legacyKey,
                                        @NotBlank @Size(max = 1000) String bookId,
                                        @NotNull Map<String, Object> payload,
                                        boolean deleted,
                                        @Positive long revision,
                                        @Positive long serverUpdatedAt) {
}
