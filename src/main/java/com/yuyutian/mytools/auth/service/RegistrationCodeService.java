package com.yuyutian.mytools.auth.service;

import com.yuyutian.mytools.auth.Model.RegisterCodeRequest;
import com.yuyutian.mytools.auth.Model.RegisterRequest;

/**
 * 注册验证码服务接口。
 *
 * @author mytools
 * @since 2026-05-29
 */
public interface RegistrationCodeService {

    /**
     * 发送注册邮箱验证码。
     *
     * @param request 注册验证码请求
     */
    void sendRegisterCode(RegisterCodeRequest request);

    /**
     * 校验注册邮箱验证码。
     *
     * @param request 注册请求
     */
    void verifyRegisterCode(RegisterRequest request);
}
