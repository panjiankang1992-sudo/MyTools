package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 书源搜索聚合视图。
 *
 * @param id 搜索请求标识
 * @param taskId 调度任务标识
 * @param status 搜索状态
 * @param keyword 关键字
 * @param mode 匹配模式
 * @param page 页码
 * @param completedShards 已完成分片数
 * @param failedShards 失败分片数
 * @param totalShards 总分片数
 * @param results 聚合结果
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record SearchView(UUID id, UUID taskId, String status, String keyword, SearchMode mode, int page,
                         int completedShards, int failedShards, int totalShards,
                         List<Map<String, Object>> results, Instant createdAt, Instant updatedAt) {
}
