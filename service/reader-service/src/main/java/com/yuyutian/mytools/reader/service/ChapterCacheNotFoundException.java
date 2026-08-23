package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

/**
 * 章节缓存不存在异常。
 */
public class ChapterCacheNotFoundException extends RuntimeException {

    /**
     * 创建章节缓存不存在异常。
     */
    public ChapterCacheNotFoundException() {
        super(ErrorCode.CHAPTER_CACHE_NOT_FOUND.code());
    }
}
