package com.yuyutian.mytools.common;

/**
 * 媒体文件未找到异常
 */
public class MediaNotFoundException extends RuntimeException {
    public MediaNotFoundException(String message) {
        super(message);
    }
}
