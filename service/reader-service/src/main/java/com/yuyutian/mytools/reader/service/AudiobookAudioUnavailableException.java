package com.yuyutian.mytools.reader.service;

/**
 * 有声书章节音频尚不可读取或其受管存储不可用。
 */
public class AudiobookAudioUnavailableException extends RuntimeException {

    /**
     * 创建不含下游敏感细节的音频不可用异常。
     */
    public AudiobookAudioUnavailableException() {
        super("audiobook chapter audio is unavailable");
    }

    /**
     * 创建包含内部原因的音频不可用异常。
     *
     * @param cause 下游调用失败原因
     */
    public AudiobookAudioUnavailableException(Throwable cause) {
        super("audiobook chapter audio is unavailable", cause);
    }
}
