package com.yuyutian.mytools.reader.service;

import java.util.UUID;

/**
 * 有声书生成运行不存在或不属于调用方。
 */
public class AudiobookGenerationNotFoundException extends RuntimeException {

    /**
     * 创建不存在异常。
     *
     * @param id 生成运行标识
     */
    public AudiobookGenerationNotFoundException(UUID id) {
        super("audiobook generation was not found: " + id);
    }
}
