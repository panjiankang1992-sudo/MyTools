package com.yuyutian.mytools.reader.task;

/**
 * 旧书源电子书导入旁路事件。
 *
 * @param legacyTaskId 旧任务标识
 * @param ownerId 所有者标识
 * @param sourceUrl 书源地址
 * @param bookUrl 图书地址
 * @param title 图书标题
 * @param author 图书作者
 */
public record ReaderImportSidecarRequested(String legacyTaskId, long ownerId, String sourceUrl,
                                            String bookUrl, String title, String author) {
}
