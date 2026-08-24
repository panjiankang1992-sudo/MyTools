package com.yuyutian.mytools.messaging.service;

/**
 * IMAP 入站执行失败异常。
 */
public class EmailIngressException extends RuntimeException {
    /**
     * 创建入站执行失败异常。
     *
     * @param cause 原始异常
     */
    public EmailIngressException(Throwable cause) {
        super(cause);
    }
}
