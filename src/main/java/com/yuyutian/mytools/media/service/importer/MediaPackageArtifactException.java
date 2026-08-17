package com.yuyutian.mytools.media.service.importer;

/**
 * 媒体资源包协议或路径校验失败异常。
 */
public class MediaPackageArtifactException extends RuntimeException {

    /**
     * 使用固定安全信息创建异常。
     *
     * @param message 不包含文件内容或凭据的错误信息
     */
    public MediaPackageArtifactException(String message) {
        super(message);
    }

    /**
     * 使用固定安全信息和原因创建异常。
     *
     * @param message 不包含文件内容或凭据的错误信息
     * @param cause 原始异常
     */
    public MediaPackageArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}
