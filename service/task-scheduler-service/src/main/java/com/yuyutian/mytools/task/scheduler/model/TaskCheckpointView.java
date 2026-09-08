package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;
import java.util.Map;

/**
 * 任务检查点视图。
 *
 * @param key 检查点键
 * @param version 版本
 * @param value 检查点内容
 * @param updatedAt 更新时间
 * @param replayed 是否为幂等重放
 */
public record TaskCheckpointView(
        String key,
        long version,
        Map<String, Object> value,
        Instant updatedAt,
        boolean replayed
) {
}
