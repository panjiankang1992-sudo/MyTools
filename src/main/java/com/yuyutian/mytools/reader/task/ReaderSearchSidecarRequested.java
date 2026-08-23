package com.yuyutian.mytools.reader.task;

import java.util.List;
import java.util.Map;

/**
 * 现有书源搜索创建后的旁路任务事件。
 *
 * @param userId 用户标识
 * @param keyword 关键词
 * @param page 页码
 * @param mode 搜索模式
 * @param sources 书源不可变快照
 */
public record ReaderSearchSidecarRequested(
        Long userId,
        String keyword,
        int page,
        String mode,
        List<Map<String, Object>> sources
) {
}
