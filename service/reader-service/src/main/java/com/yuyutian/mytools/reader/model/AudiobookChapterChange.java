package com.yuyutian.mytools.reader.model;

/**
 * 有声书章节相对于上一书籍版本的变化类型。
 */
public enum AudiobookChapterChange {
    UNCHANGED,
    APPENDED,
    MODIFIED,
    MOVED_OR_RENUMBERED,
    REMOVED
}
