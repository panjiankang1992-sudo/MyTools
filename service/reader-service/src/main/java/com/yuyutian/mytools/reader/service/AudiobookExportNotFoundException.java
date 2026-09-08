package com.yuyutian.mytools.reader.service;

import java.util.UUID;

/**
 * 有声书导出不存在，或不属于当前所有者。
 */
public class AudiobookExportNotFoundException extends RuntimeException {

    /**
     * 创建导出不存在异常。
     *
     * @param id 导出标识
     */
    public AudiobookExportNotFoundException(UUID id) {
        super("audiobook export was not found: " + id);
    }
}
