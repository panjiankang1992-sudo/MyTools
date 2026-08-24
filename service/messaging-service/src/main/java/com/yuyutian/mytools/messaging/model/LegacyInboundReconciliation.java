package com.yuyutian.mytools.messaging.model;

/**
 * 历史消息迁移目标侧集合证据。
 *
 * @param migrationKey 迁移键
 * @param itemCount 已迁移条目数
 * @param collectionSha256 稳定集合摘要
 */
public record LegacyInboundReconciliation(String migrationKey, int itemCount,
                                          String collectionSha256) {
}
