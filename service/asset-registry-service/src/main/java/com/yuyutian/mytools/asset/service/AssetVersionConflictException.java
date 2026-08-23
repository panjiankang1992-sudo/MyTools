package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.ErrorCode;

/**
 * 资产乐观版本冲突异常。
 */
public class AssetVersionConflictException extends RuntimeException {

    /**
     * 创建资产版本冲突异常。
     */
    public AssetVersionConflictException() {
        super(ErrorCode.VERSION_CONFLICT.code());
    }
}
