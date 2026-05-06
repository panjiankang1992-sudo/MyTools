package com.yuyutian.mytools.auth.service;

import com.yuyutian.mytools.auth.Model.*;

/**
 * 认证服务接口。
 *
 * @author mytools
 * @since 2026-04-22
 */
public interface AuthService {

    /**
     * 用户注册。
     *
     * @param request 注册请求参数
     * @return 注册响应（包含用户ID和JWT令牌）
     */
    RegisterResponse register(RegisterRequest request);

    /**
     * 用户登录。
     *
     * @param request 登录请求参数
     * @return 登录响应（包含用户信息和JWT令牌）
     */
    LoginResponse login(LoginRequest request);

    /**
     * 刷新访问令牌。
     *
     * @param oldToken 旧访问令牌
     * @return 新令牌响应
     */
    RefreshResponse refreshToken(String oldToken);

    /**
     * 用户登出。
     * 使当前Access Token失效。
     *
     * @param authHeader Authorization头（Bearer token）
     */
    void logout(String authHeader);
}