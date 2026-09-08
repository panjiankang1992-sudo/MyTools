package com.yuyutian.mytools.reader.service;

/**
 * 已完成有声书导出归档暂时无法读取。
 */
public class AudiobookExportUnavailableException extends RuntimeException {

    /**
     * 创建不含下游敏感细节的归档不可用异常。
     */
    public AudiobookExportUnavailableException() {
        super("audiobook export archive is unavailable");
    }

    /**
     * 创建包含内部原因的归档不可用异常。
     *
     * @param cause 下游调用失败原因
     */
    public AudiobookExportUnavailableException(Throwable cause) {
        super("audiobook export archive is unavailable", cause);
    }
}
