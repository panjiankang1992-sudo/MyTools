package com.yuyutian.mytools.auth.controller;

import com.yuyutian.mytools.auth.Model.*;
import com.yuyutian.mytools.auth.service.AuthService;
import com.yuyutian.mytools.common.MessageHelper;
import com.yuyutian.mytools.common.Result;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器。
 *
 * @author mytools
 * @since 2026-04-22
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 发送注册邮箱验证码。
     *
     * @param request 注册验证码请求参数
     * @return 成功响应
     */
    @PostMapping("/register/code")
    public ResponseEntity<Result<Void>> sendRegisterCode(@Valid @RequestBody RegisterCodeRequest request) {
        authService.sendRegisterCode(request);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.verification.code.send"), null));
    }

    /**
     * 用户注册接口。
     *
     * @param request 注册请求参数
     * @return 注册响应（201 Created）
     */
    @PostMapping("/register")
    public ResponseEntity<Result<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(MessageHelper.getMessage("success.register"), response));
    }

    /**
     * 用户登录接口。
     *
     * @param request 登录请求参数
     * @return 登录响应（200 OK）
     */
    @PostMapping("/login")
    public ResponseEntity<Result<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.login"), response));
    }

    /**
     * 刷新访问令牌接口。
     *
     * @param authHeader Authorization头（Bearer token）
     * @return 新令牌响应（200 OK）
     */
    @PostMapping("/refresh")
    public ResponseEntity<Result<RefreshResponse>> refresh(@RequestHeader("Authorization") String authHeader) {
        RefreshResponse response = authService.refreshToken(authHeader);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.refresh"), response));
    }

    /**
     * 用户登出接口。
     * 使当前Access Token失效。
     *
     * @param authHeader Authorization头（Bearer token）
     * @return 成功响应（200 OK）
     */
    @PostMapping("/logout")
    public ResponseEntity<Result<Void>> logout(@RequestHeader("Authorization") String authHeader) {
        authService.logout(authHeader);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.logout"), null));
    }
}
