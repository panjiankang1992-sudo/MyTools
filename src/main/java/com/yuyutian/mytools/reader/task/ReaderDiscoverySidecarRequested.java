package com.yuyutian.mytools.reader.task;

/**
 * 旧书源发现旁路事件。
 *
 * @param legacyTaskId 旧任务标识
 * @param ownerId 所有者标识
 * @param url 书源仓库地址
 */
public record ReaderDiscoverySidecarRequested(String legacyTaskId, long ownerId, String url) {
}
