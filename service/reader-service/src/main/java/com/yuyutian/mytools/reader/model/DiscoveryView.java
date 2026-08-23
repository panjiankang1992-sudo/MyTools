package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 书源发现任务视图。
 *
 * @param id 请求标识
 * @param taskId 调度任务标识
 * @param status 状态
 * @param url 仓库地址
 * @param processed 已处理数量
 * @param saved 已保存数量
 * @param rejected 已拒绝数量
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DiscoveryView(UUID id, UUID taskId, String status, String url, int processed, int saved,
                            int rejected, Instant createdAt, Instant updatedAt) {
}
