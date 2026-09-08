package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

/**
 * 有声书新生成工作未对当前所有者开放异常。
 */
public class AudiobookGenerationUnavailableException extends RuntimeException {

    /**
     * 创建有声书灰度未开放异常。
     */
    public AudiobookGenerationUnavailableException() {
        super(ErrorCode.AUDIOBOOK_GENERATION_UNAVAILABLE.code());
    }
}
