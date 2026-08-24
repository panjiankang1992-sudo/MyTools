package com.yuyutian.mytools.messaging.model;

/** MsgService 参考数据迁移对账证据。 */
public record LegacyReferenceDataReconciliation(String migrationKey, int templateCount,
                                                int recipientCount, String collectionSha256) {
}
