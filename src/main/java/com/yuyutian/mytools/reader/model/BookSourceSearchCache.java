package com.yuyutian.mytools.reader.model;

/**
 * 用户书源搜索缓存记录。
 *
 * @param userId 用户ID
 * @param normalizedKeyword 规范化关键词
 * @param queryMode 查询方式
 * @param sourceId 书源同步ID
 * @param page 页码
 * @param sourceRevision 书源版本
 * @param cacheStatus 缓存状态
 * @param resultsJson 搜索结果JSON，空数组表示已确认无结果
 * @param resultCount 结果数量
 * @param createdAt 创建时间戳
 * @param expiresAt 过期时间戳
 */
public record BookSourceSearchCache(Long userId, String normalizedKeyword, String queryMode, String sourceId,
                                    int page, long sourceRevision, String cacheStatus, String resultsJson, int resultCount,
                                    long createdAt, long expiresAt) {
}
