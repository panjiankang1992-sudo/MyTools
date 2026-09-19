package com.yuyutian.mytools.messaging.service;

/**
 * 渠道提供方返回需要原样保留 HTTP 状态的永久回复失败时抛出的异常。
 */
public class InboundReplyProviderFailureException extends RuntimeException {

    private final int statusCode;

    /**
     * 创建渠道回复失败异常。
     *
     * @param statusCode 渠道提供方返回的 HTTP 状态码
     * @param cause 渠道提供方返回的原始异常
     */
    public InboundReplyProviderFailureException(int statusCode, Throwable cause) {
        super(cause);
        if (statusCode != 409 && statusCode != 422) {
            throw new IllegalArgumentException("Inbound reply provider status is unsupported");
        }
        this.statusCode = statusCode;
    }

    /**
     * 返回需要向上游保留的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int statusCode() {
        return statusCode;
    }
}
