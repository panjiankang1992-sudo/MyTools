package com.yuyutian.mytools.toolbox.service.curriculum;

/**
 * 教材目录校验失败：启动阶段发现清单、文件或哈希不一致时抛出，进程必须失败退出。
 */
public class CatalogValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造校验失败异常。
     *
     * @param message 面向运维的英文说明，不包含教材正文
     */
    public CatalogValidationException(String message) {
        super(message);
    }

    /**
     * 构造带原因的校验失败异常。
     *
     * @param message 面向运维的英文说明
     * @param cause   原始异常
     */
    public CatalogValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
