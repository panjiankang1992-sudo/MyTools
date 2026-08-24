package com.yuyutian.mytools.reader.model;

/**
 * 旧 Reader 迁移在目标端重新计算的集合证据。
 */
public record LegacyReaderMigrationEvidence(String migrationKey, long itemCount, String digestSha256) {
}
