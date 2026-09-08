package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

/**
 * 有声书冻结正文超过成本控制上限异常。
 */
public class AudiobookCharacterLimitExceededException extends RuntimeException {

    /**
     * 创建冻结正文超限异常。
     */
    public AudiobookCharacterLimitExceededException() {
        super(ErrorCode.AUDIOBOOK_CHARACTER_LIMIT_EXCEEDED.code());
    }
}
