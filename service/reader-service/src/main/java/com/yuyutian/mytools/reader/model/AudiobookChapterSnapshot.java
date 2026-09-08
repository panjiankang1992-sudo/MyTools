package com.yuyutian.mytools.reader.model;

/**
 * 参与有声书增量规划的章节不可变快照。
 *
 * @param chapterIndex 当前目录顺序
 * @param identitySha256 章节稳定身份摘要
 * @param contentSha256 规范化正文摘要
 * @param title 章节标题
 */
public record AudiobookChapterSnapshot(int chapterIndex, String identitySha256,
                                       String contentSha256, String title) {
}
