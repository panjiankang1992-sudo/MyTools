package com.yuyutian.mytools.storage.model;

/**
 * 原生对象写入结果。
 *
 * @param operationId 操作标识
 * @param contentLength 内容长度
 * @param sha256 内容摘要
 * @param created 是否由本操作确认创建
 */
public record NativeWriteResult(java.util.UUID operationId, long contentLength, String sha256, boolean created) {
}
