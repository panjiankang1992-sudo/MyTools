package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 书库索引重建批次结果。
 */
public record LibraryRebuildBatchResult(int indexed, long indexedTotal, UUID nextCursor, boolean done) {
}
