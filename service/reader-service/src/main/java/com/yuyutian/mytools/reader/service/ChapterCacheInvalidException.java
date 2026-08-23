package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

/**
 * 章节缓存批次无效异常。
 */
public class ChapterCacheInvalidException extends RuntimeException {

    /**
     * 创建章节缓存批次无效异常。
     */
    public ChapterCacheInvalidException() {
        super(ErrorCode.CHAPTER_CACHE_INVALID.code());
    }
}
