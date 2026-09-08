package com.yuyutian.mytools.reader.model;

/**
 * 一个章节在本次有声书运行中的处理计划。
 *
 * @param chapter 当前版本章节
 * @param change 变更类型
 * @param reusable 是否可复用历史分析与音频
 * @param previousChapterIndex 历史章节顺序；新增章节为 null
 */
public record AudiobookChapterPlan(AudiobookChapterSnapshot chapter, AudiobookChapterChange change,
                                   boolean reusable, Integer previousChapterIndex) {
}
