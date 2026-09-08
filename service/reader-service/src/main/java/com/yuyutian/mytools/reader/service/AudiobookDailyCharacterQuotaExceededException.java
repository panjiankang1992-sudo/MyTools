package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ErrorCode;

/**
 * 有声书所有者在一个自然日内超出可提交合成的正文码点预算异常。
 */
public class AudiobookDailyCharacterQuotaExceededException extends RuntimeException {

    /**
     * 创建每日正文预算超限异常。
     */
    public AudiobookDailyCharacterQuotaExceededException() {
        super(ErrorCode.AUDIOBOOK_DAILY_CHARACTER_QUOTA_EXCEEDED.code());
    }
}
