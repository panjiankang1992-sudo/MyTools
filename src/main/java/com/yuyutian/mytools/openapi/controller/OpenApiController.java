package com.yuyutian.mytools.openapi.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.openapi.model.OpenProfileResponse;
import com.yuyutian.mytools.user.Model.UserInfoResponse;
import com.yuyutian.mytools.user.service.UserService;
import com.yuyutian.mytools.webdav.model.WebdavAccountPublicResponse;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 公开 OpenAPI，供外部系统调用。
 * 认证方式：Authorization: Bearer <jwt_token>
 * 用户凭本人 JWT token 获取自己的用户信息和 WebDAV 配置。
 */
@RestController
@RequiredArgsConstructor
public class OpenApiController {

    private final JwtUtils jwtUtils;
    private final UserService userService;
    private final WebdavAccountService webdavAccountService;

    /**
     * 获取用户公开信息及 WebDAV 配置（密码为 AES 加密密文）。
     *
     * @param authorization Bearer <jwt_token>
     */
    @GetMapping("/api/public/profile")
    public ResponseEntity<Result<OpenProfileResponse>> getProfile(
            @RequestHeader("Authorization") String authorization) {

        String token = extractToken(authorization);
        Long userId = jwtUtils.getUserIdFromToken(token);

        UserInfoResponse user = userService.getUserInfo(userId);
        WebdavAccountPublicResponse webdav = webdavAccountService.getPublicByUserId(userId);

        OpenProfileResponse response = new OpenProfileResponse();
        // 用户基本信息字段
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

        // WebDAV 字段
        if (webdav != null) {
            response.setWebdavType(webdav.getType());
            response.setWebdavUrl(webdav.getUrl());
            response.setWebdavUsername(webdav.getUsername());
            response.setWebdavEncryptedPassword(webdav.getEncryptedPassword());
            response.setWebdavPasswordSet(webdav.getPasswordSet());
        }

        return ResponseEntity.ok(Result.success(response));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }
}
