package com.yuyutian.mytools.messaging.model;

/** 历史发件迁移对账证据。 */
public record LegacyOutboundReconciliation(String migrationKey, int itemCount,
                                           String collectionSha256) {
}
