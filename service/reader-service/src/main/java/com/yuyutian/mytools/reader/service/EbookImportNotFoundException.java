package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

import java.util.UUID;

/**
 * 电子书导入请求不存在异常。
 */
public class EbookImportNotFoundException extends RuntimeException {

    /**
     * 创建电子书导入请求不存在异常。
     *
     * @param requestId 请求标识
     */
    public EbookImportNotFoundException(UUID requestId) {
        super(ErrorCode.EBOOK_IMPORT_NOT_FOUND.code() + ":" + requestId);
    }
}
