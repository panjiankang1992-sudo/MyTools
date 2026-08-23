package com.yuyutian.mytools.reader.model;

/**
 * 章节缓存维护批次结果。
 */
public record CacheMaintenanceBatchResult(int deleted, long deletedTotal, String status) {
}
