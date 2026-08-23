package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

import java.util.UUID;

/**
 * 电子书目录尚未就绪异常。
 */
public class EbookCatalogNotReadyException extends RuntimeException {

    /**
     * 创建电子书目录尚未就绪异常。
     *
     * @param requestId 导入请求标识
     */
    public EbookCatalogNotReadyException(UUID requestId) {
        super(ErrorCode.EBOOK_CATALOG_NOT_READY.code() + ":" + requestId);
    }
}
