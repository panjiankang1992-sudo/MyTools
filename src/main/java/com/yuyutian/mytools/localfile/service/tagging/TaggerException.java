package com.yuyutian.mytools.localfile.service.tagging;

/**
 * 打标签服务异常。
 *
 * @author mytools
 * @since 2026-05-04
 */
public class TaggerException extends RuntimeException {

    public TaggerException(String message) {
        super(message);
    }

    public TaggerException(String message, Throwable cause) {
        super(message, cause);
    }
}
