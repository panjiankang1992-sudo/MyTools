package com.yuyutian.mytools.reader.service;

/**
 * 书源规则执行器不可用异常。
 */
public class ReaderRuntimeUnavailableException extends RuntimeException {
    /** 创建不暴露下游响应内容的异常。 */
    public ReaderRuntimeUnavailableException() {
        super("Reader runtime is unavailable");
    }
}
