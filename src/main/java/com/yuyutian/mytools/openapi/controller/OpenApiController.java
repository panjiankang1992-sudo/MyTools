package com.yuyutian.mytools.openapi.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.MessageHelper;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.openapi.model.OpenProfileResponse;
import com.yuyutian.mytools.user.Model.ChangePasswordRequest;
import com.yuyutian.mytools.user.Model.UpdateUserInfoRequest;
import com.yuyutian.mytools.user.Model.UserInfoResponse;
import com.yuyutian.mytools.user.service.UserService;
import com.yuyutian.mytools.webdav.model.UpdateWebdavAccountRequest;
import com.yuyutian.mytools.webdav.model.WebdavAccountPublicResponse;
import com.yuyutian.mytools.webdav.model.WebdavAccountResponse;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 公开 OpenAPI，供外部系统调用。
 * 认证方式：Authorization: Bearer <jwt_token>
 * 用户凭本人 JWT token 获取和修改自己的用户信息及 WebDAV 配置。
 */
@RestController
@RequiredArgsConstructor
public class OpenApiController {

    private final JwtUtils jwtUtils;
    private final UserService userService;
    private final WebdavAccountService webdavAccountService;

    // ==================== 查询接口 ====================

    /**
     * 获取用户公开信息及 WebDAV 配置（密码为 AES 加密密文）。
     *
     * @param authorization Bearer <jwt_token>
     */
    @GetMapping("/api/public/profile")
    public ResponseEntity<Result<OpenProfileResponse>> getProfile(
            @RequestHeader("Authorization") String authorization) {

        Long userId = resolveUserId(authorization);
        UserInfoResponse user = userService.getUserInfo(userId);
        WebdavAccountPublicResponse webdav = webdavAccountService.getPublicByUserId(userId);

        OpenProfileResponse response = buildProfileResponse(user, webdav);
        return ResponseEntity.ok(Result.success(response));
    }

    // ==================== 更新接口 ====================

    /**
     * 更新用户基本信息。
     *
     * @param authorization Bearer <jwt_token>
     * @param request       更新请求
     */
    @PutMapping("/api/public/profile")
    public ResponseEntity<Result<UserInfoResponse>> updateProfile(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UpdateUserInfoRequest request) {

        Long userId = resolveUserId(authorization);
        UserInfoResponse updated = userService.updateUserInfo(userId, request);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.update"), updated));
    }

    /**
     * 修改登录密码。
     *
     * @param authorization Bearer <jwt_token>
     * @param request       修改密码请求（旧密码 + 新密码）
     */
    @PutMapping("/api/public/password")
    public ResponseEntity<Result<Void>> changePassword(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ChangePasswordRequest request) {

        Long userId = resolveUserId(authorization);
        userService.changePassword(userId, request);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.password.change"), null));
    }

    /**
     * 更新 WebDAV 配置。
     *
     * @param authorization Bearer <jwt_token>
     * @param request       WebDAV 更新请求
     */
    @PutMapping("/api/public/webdav")
    public ResponseEntity<Result<WebdavAccountResponse>> updateWebdav(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UpdateWebdavAccountRequest request) {

        Long userId = resolveUserId(authorization);
        WebdavAccountResponse updated = webdavAccountService.saveOrUpdate(userId, request);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.update"), updated));
    }

    // ==================== 内部方法 ====================

    private Long resolveUserId(String authHeader) {
        String token = extractToken(authHeader);
        return jwtUtils.getUserIdFromToken(token);
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }

    private OpenProfileResponse buildProfileResponse(UserInfoResponse user, WebdavAccountPublicResponse webdav) {
        OpenProfileResponse response = new OpenProfileResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setGender(user.getGender());
        response.setBirthday(user.getBirthday());
        response.setAddress(user.getAddress());
        response.setHobbies(user.getHobbies());
        response.setSignature(user.getSignature());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setRegisterTime(user.getRegisterTime());
        response.setLastLoginTime(user.getLastLoginTime());

        if (webdav != null) {
            response.setWebdavType(webdav.getType());
            response.setWebdavUrl(webdav.getUrl());
            response.setWebdavUsername(webdav.getUsername());
            response.setWebdavEncryptedPassword(webdav.getEncryptedPassword());
            response.setWebdavPasswordSet(webdav.getPasswordSet());
        }

        return response;
    }
}
