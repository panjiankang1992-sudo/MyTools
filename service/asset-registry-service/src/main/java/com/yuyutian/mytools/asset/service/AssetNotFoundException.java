package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.ErrorCode;

import java.util.UUID;

/**
 * 资产不存在异常。
 */
public class AssetNotFoundException extends RuntimeException {

    /**
     * 创建资产不存在异常。
     */
    public AssetNotFoundException(UUID id) {
        super(ErrorCode.ASSET_NOT_FOUND.code() + ":" + id);
    }
}
