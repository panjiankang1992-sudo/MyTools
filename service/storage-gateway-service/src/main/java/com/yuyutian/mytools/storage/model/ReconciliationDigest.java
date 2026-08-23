package com.yuyutian.mytools.storage.model;

/**
 * 稳定排序对象集合的对账摘要。
 *
 * @param itemCount 对象数
 * @param contentSha256 集合摘要
 */
public record ReconciliationDigest(long itemCount, String contentSha256) {
}
