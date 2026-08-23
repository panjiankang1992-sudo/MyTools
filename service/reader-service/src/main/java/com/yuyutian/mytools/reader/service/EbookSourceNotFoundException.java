package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

import java.util.UUID;

/**
 * 可用书源不存在异常。
 */
public class EbookSourceNotFoundException extends RuntimeException {

    /**
     * 创建可用书源不存在异常。
     *
     * @param sourceId 书源标识
     */
    public EbookSourceNotFoundException(UUID sourceId) {
        super(ErrorCode.EBOOK_SOURCE_NOT_FOUND.code() + ":" + sourceId);
    }
}
