package com.yuyutian.mytools.messaging.model;

/**
 * IMAP 轮询结果。
 *
 * @param accountKey 账户逻辑键
 * @param examinedCount 检查数量
 * @param acceptedCount 新增或幂等接收数量
 * @param lastUid 最后处理 UID
 */
public record EmailPollResult(String accountKey, int examinedCount, int acceptedCount, long lastUid) {
}
