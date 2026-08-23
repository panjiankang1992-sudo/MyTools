package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

import java.util.UUID;

/**
 * 搜索请求不存在异常。
 */
public class SearchNotFoundException extends RuntimeException {

    /**
     * 创建搜索请求不存在异常。
     *
     * @param requestId 搜索请求标识
     */
    public SearchNotFoundException(UUID requestId) {
        super(ErrorCode.SEARCH_NOT_FOUND.code() + ":" + requestId);
    }
}
