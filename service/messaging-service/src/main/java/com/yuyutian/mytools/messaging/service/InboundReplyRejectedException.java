package com.yuyutian.mytools.messaging.service;

/**
 * 渠道提供方永久拒绝入站消息回复时抛出的异常。
 */
public class InboundReplyRejectedException extends RuntimeException {

    /**
     * 创建渠道回复拒绝异常。
     *
     * @param cause 渠道提供方返回的原始异常
     */
    public InboundReplyRejectedException(Throwable cause) {
        super(cause);
    }
}
