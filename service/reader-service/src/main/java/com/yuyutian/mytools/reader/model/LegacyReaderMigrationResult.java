package com.yuyutian.mytools.reader.model;

import java.util.List;

/**
 * 旧 Reader 用户数据迁移批次结果。
 */
public record LegacyReaderMigrationResult(int accepted, int skipped, int rejected,
                                          List<String> rejectionKeys, String digestSha256) {
}
