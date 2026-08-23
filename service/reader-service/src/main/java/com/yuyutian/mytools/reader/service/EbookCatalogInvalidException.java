package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

/**
 * 电子书目录批次无效异常。
 */
public class EbookCatalogInvalidException extends RuntimeException {

    /**
     * 创建电子书目录批次无效异常。
     */
    public EbookCatalogInvalidException() {
        super(ErrorCode.EBOOK_CATALOG_INVALID.code());
    }
}
