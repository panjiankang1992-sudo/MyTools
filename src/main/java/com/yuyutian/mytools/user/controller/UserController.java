package com.yuyutian.mytools.user.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.MessageHelper;
import com.yuyutian.mytools.common.PageResult;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.user.Model.*;
import com.yuyutian.mytools.user.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;

/**
 * 用户控制器。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final JwtUtils jwtUtils;

    @Autowired
    public UserController(UserService userService, JwtUtils jwtUtils) {
        this.userService = userService;
        this.jwtUtils = jwtUtils;
    }

    /**
     * 获取当前用户信息。
     *
     * @return 用户信息
     */
    @GetMapping("/info")
    public ResponseEntity<Result<UserInfoResponse>> getUserInfo(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserIdFromToken(authHeader);
        UserInfoResponse response = userService.getUserInfo(userId);
        return ResponseEntity.ok(Result.success(response));
    }

    /**
     * 更新当前用户信息。
     *
     * @param authHeader Authorization头
     * @param request 更新请求
     * @return 更新后的用户信息
     */
    @PutMapping("/info")
    public ResponseEntity<Result<UserInfoResponse>> updateUserInfo(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateUserInfoRequest request) {
        Long userId = extractUserIdFromToken(authHeader);
        UserInfoResponse response = userService.updateUserInfo(userId, request);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.update"), response));
    }

    /**
     * 修改密码。
     *
     * @param authHeader Authorization头
     * @param request 修改密码请求
     * @return 成功响应
     */
    @PutMapping("/password")
    public ResponseEntity<Result<Void>> changePassword(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody ChangePasswordRequest request) {
        Long userId = extractUserIdFromToken(authHeader);
        userService.changePassword(userId, request);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.password.change"), null));
    }

    /**
     * 更新用户状态（管理员）。
     *
     * @param id 目标用户ID
     * @param authHeader Authorization头
     * @param request 状态更新请求
     * @return 用户状态响应
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<UserStatusResponse>> updateUserStatus(
            @PathVariable("id") Long id,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateStatusRequest request) {
        Long adminUserId = extractUserIdFromToken(authHeader);
        UserStatusResponse response = userService.updateUserStatus(id, request.getStatus(), adminUserId);
        return ResponseEntity.ok(Result.success(response));
    }

    /**
     * 删除用户（管理员）。
     *
     * @param id 目标用户ID
     * @param authHeader Authorization头
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Void>> deleteUser(
            @PathVariable("id") Long id,
            @RequestHeader("Authorization") String authHeader) {
        Long adminUserId = extractUserIdFromToken(authHeader);
        userService.deleteUser(id, adminUserId);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.delete"), null));
    }

    /**
     * 获取用户列表（管理员，分页）。
     *
     * @param keyword 关键字（用户名或邮箱模糊匹配）
     * @param status 状态过滤
     * @param page 页码（从1开始）
     * @param pageSize 每页记录数
     * @param authHeader Authorization头
     * @return 分页用户列表
     */
    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<PageResult<UserInfoResponse>>> getUserPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestHeader("Authorization") String authHeader) {
        extractUserIdFromToken(authHeader); // 验证管理员身份
        PageResult<UserInfoResponse> result = userService.getUserPage(keyword, status, page, pageSize);
        return ResponseEntity.ok(Result.success(result));
    }

    /**
     * 创建用户（管理员）。
     *
     * @param request 创建用户请求
     * @param authHeader Authorization头
     * @return 创建的用户响应
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<CreateUserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request,
            @RequestHeader("Authorization") String authHeader) {
        Long adminUserId = extractUserIdFromToken(authHeader);
        CreateUserResponse response = userService.createUser(request, adminUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(MessageHelper.getMessage("success.create"), response));
    }

    /**
     * 更新用户（管理员）。
     *
     * @param id 目标用户ID
     * @param request 更新用户请求
     * @param authHeader Authorization头
     * @return 更新后的用户信息
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<UserInfoResponse>> updateUser(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateUserRequest request,
            @RequestHeader("Authorization") String authHeader) {
        Long adminUserId = extractUserIdFromToken(authHeader);
        UserInfoResponse response = userService.updateUser(id, request, adminUserId);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.update"), response));
    }

    /**
     * 为用户分配角色（管理员）。
     *
     * @param id 目标用户ID
     * @param roleCode 角色编码
     * @param authHeader Authorization头
     * @return 成功响应
     */
    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Void>> assignRole(
            @PathVariable("id") Long id,
            @RequestParam String roleCode,
            @RequestHeader("Authorization") String authHeader) {
        Long adminUserId = extractUserIdFromToken(authHeader);
        userService.assignRole(id, roleCode, adminUserId);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.role.assign"), null));
    }

    /**
     * 从Authorization头中提取用户ID。
     */
    private Long extractUserIdFromToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return jwtUtils.getUserIdFromToken(token);
        }
        // 从SecurityContext获取（已通过JWT过滤器验证）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            // 从authentication的name中获取userId
            String name = auth.getName();
            if (name != null && name.matches("\\d+")) {
                return Long.parseLong(name);
            }
        }
        throw new BusinessException(ErrorCode.AUTH_002);
    }
}