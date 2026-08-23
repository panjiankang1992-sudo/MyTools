package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.ErrorCode;

/**
 * 资产请求业务字段无效异常。
 */
public class AssetInputInvalidException extends RuntimeException {

    /**
     * 创建资产输入无效异常。
     */
    public AssetInputInvalidException() {
        super(ErrorCode.INPUT_INVALID.code());
    }
}
