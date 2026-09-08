package com.yuyutian.mytools.reader.service;

import java.util.UUID;

/**
 * 请求的电子书资产不存在或不属于调用方。
 */
public class AudiobookAssetNotFoundException extends RuntimeException {

    /**
     * 创建不存在异常。
     *
     * @param id 电子书资产标识
     */
    public AudiobookAssetNotFoundException(UUID id) {
        super("audiobook source asset was not found: " + id);
    }
}
