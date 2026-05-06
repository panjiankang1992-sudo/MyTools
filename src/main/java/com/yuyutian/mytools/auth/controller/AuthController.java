package com.yuyutian.mytools.auth.controller;

import com.yuyutian.mytools.auth.Model.*;
import com.yuyutian.mytools.auth.service.AuthService;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.utils.PasswordUtils;
import com.yuyutian.mytools.user.mapper.UserMapper;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserMapper userMapper;

    @Autowired
    public AuthController(AuthService authService, UserMapper userMapper) {
        this.authService = authService;
        this.userMapper = userMapper;
    }

    /**
     * 临时密码重置接口（仅用于修复损坏的admin密码）。
     */
    @PostMapping("/reset-admin-password")
    public ResponseEntity<Result<String>> resetAdminPassword() {
        com.yuyutian.mytools.user.Model.User admin = userMapper.findByUsername("admin");
        if (admin == null) {
            return ResponseEntity.ok(Result.success("Admin用户不存在", null));
        }
        String newHash = PasswordUtils.encode("admin123");
        int rows = userMapper.updatePassword(admin.getId(), newHash);
        log.info("Admin password reset, userId={}, rows affected: {}", admin.getId(), rows);
        return ResponseEntity.ok(Result.success("Admin密码已重置为admin123", null));
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
                .body(Result.success("注册成功", response));
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
        return ResponseEntity.ok(Result.success("登录成功", response));
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
        return ResponseEntity.ok(Result.success("刷新成功", response));
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
        return ResponseEntity.ok(Result.success("登出成功", null));
    }
}