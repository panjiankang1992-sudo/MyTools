package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 创建章节预取请求。
 *
 * @param ownerId 所有者标识
 * @param idempotencyKey 业务幂等键
 * @param sourceId 书源标识
 * @param bookUrl 图书地址
 * @param chapterIndexes 需要预取的章节序号
 */
public record CreateChapterPrefetchRequest(@NotNull Long ownerId,
                                           @NotBlank @Size(max = 255) String idempotencyKey,
                                           @NotNull UUID sourceId,
                                           @NotBlank @Size(max = 4096) String bookUrl,
                                           @NotEmpty @Size(max = 100)
                                           List<@NotNull @PositiveOrZero Integer> chapterIndexes) {
}
