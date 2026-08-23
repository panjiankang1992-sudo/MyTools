package com.yuyutian.mytools.messaging.model;

/**
 * 历史消息迁移批次结果。
 *
 * @param dryRun 是否仅校验
 * @param accepted 新接受数量
 * @param skipped 幂等跳过数量
 * @param rejected 冲突数量
 * @param digestSha256 批次确定性摘要
 */
public record LegacyInboundMigrationResult(boolean dryRun, int accepted, int skipped,
                                           int rejected, String digestSha256) {
}
