package com.yuyutian.mytools.webdav.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.webdav.model.WebdavAccountResponse;
import com.yuyutian.mytools.webdav.model.UpdateWebdavAccountRequest;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Webdav账户 Controller。
 * 提供当前用户的Webdav账户查询和更新能力。
 *
 * @author mytools
 * @since 2026-05-20
 */
@Slf4j
@RestController
@RequestMapping("/api/user/webdav")
@RequiredArgsConstructor
public class WebdavAccountController {

    private final WebdavAccountService webdavAccountService;
    private final JwtUtils jwtUtils;

    /**
     * 获取当前用户的Webdav账户信息。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> getWebdavAccount(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        WebdavAccountResponse data = webdavAccountService.getByUserId(userId);
        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 更新当前用户的Webdav账户信息（无则创建）。
     */
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> updateWebdavAccount(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateWebdavAccountRequest request) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        WebdavAccountResponse data = webdavAccountService.saveOrUpdate(userId, request);
        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 从Authorization头提取令牌。
     */
    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }
}
