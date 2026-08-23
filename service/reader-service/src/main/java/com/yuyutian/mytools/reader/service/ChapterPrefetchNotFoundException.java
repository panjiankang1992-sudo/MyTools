package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

import java.util.UUID;

/**
 * 章节预取请求不存在异常。
 */
public class ChapterPrefetchNotFoundException extends RuntimeException {

    /**
     * 创建章节预取请求不存在异常。
     */
    public ChapterPrefetchNotFoundException(UUID id) {
        super(ErrorCode.CHAPTER_PREFETCH_NOT_FOUND.code() + ":" + id);
    }
}
