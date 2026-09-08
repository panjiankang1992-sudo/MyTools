package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 读音修订执行器回写的精确命中章节集合。
 *
 * @param chapterIndexes 命中词条的章节序号；空集合表示词条未出现在冻结正文
 */
public record AudiobookPronunciationAffectedChaptersRequest(
        @NotNull @Size(max = 20000) List<@NotNull @PositiveOrZero Integer> chapterIndexes) {
}
