package com.yuyutian.mytools.auth.messaging;

/**
 * 注册邮件加密投递载荷。
 */
public record RegistrationMailDeliveryPayload(String recipient, String subject, String body) {
}
