package com.yuyutian.mytools.messaging.model;

/** 历史发件迁移结果。 */
public record LegacyOutboundMigrationResult(boolean dryRun, int accepted, int skipped,
                                            int rejected, String digestSha256) {
}
