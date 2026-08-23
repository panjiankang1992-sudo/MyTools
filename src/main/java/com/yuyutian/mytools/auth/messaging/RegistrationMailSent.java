package com.yuyutian.mytools.auth.messaging;

/**
 * 旧注册邮件发送成功且等待事务提交的旁路事件。
 *
 * @param verificationId 验证码记录标识
 * @param email 收件地址
 * @param code 明文验证码，仅在进程内短暂传递
 */
public record RegistrationMailSent(Long verificationId, String email, String code) {
}
