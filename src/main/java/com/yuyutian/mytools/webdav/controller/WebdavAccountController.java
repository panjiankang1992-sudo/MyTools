package com.yuyutian.mytools.webdav.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.webdav.model.CreateWebdavAccountRequest;
import com.yuyutian.mytools.webdav.model.UpdateWebdavAccountRequest;
import com.yuyutian.mytools.webdav.model.WebdavAccountResponse;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Webdav账户 Controller。
 * 提供多账户 CRUD 和个人资料页兼容接口。
 *
 * @author mytools
 * @since 2026-05-20
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebdavAccountController {

    private final WebdavAccountService webdavAccountService;
    private final JwtUtils jwtUtils;

    // ========== 兼容旧接口（个人资料页使用）==========

    @GetMapping("/api/user/webdav")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> getWebdavAccount(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = resolveUserId(authHeader);
        WebdavAccountResponse data = webdavAccountService.getByUserId(userId);
        return ResponseEntity.ok(Result.success(data));
    }

    @PutMapping("/api/user/webdav")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> updateWebdavAccount(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateWebdavAccountRequest request) {
        Long userId = resolveUserId(authHeader);
        WebdavAccountResponse data = webdavAccountService.saveOrUpdate(userId, request);
        return ResponseEntity.ok(Result.success(data));
    }

    // ========== 新接口：多账号管理 ==========

    /** 列出当前用户的所有 WebDAV 账号 */
    @GetMapping("/api/webdav/accounts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<List<WebdavAccountResponse>>> listAccounts(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = resolveUserId(authHeader);
        List<WebdavAccountResponse> data = webdavAccountService.listByUserId(userId);
        return ResponseEntity.ok(Result.success(data));
    }

    /** 获取默认账号 */
    @GetMapping("/api/webdav/accounts/default")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> getDefaultAccount(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = resolveUserId(authHeader);
        WebdavAccountResponse data = webdavAccountService.getDefaultByUserId(userId);
        return ResponseEntity.ok(Result.success(data));
    }

    /** 获取单个账号详情 */
    @GetMapping("/api/webdav/accounts/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> getAccount(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long accountId) {
        Long userId = resolveUserId(authHeader);
        WebdavAccountResponse account = webdavAccountService.getById(accountId);
        if (!account.getUserId().equals(userId)) {
            throw new BusinessException("40003", "无权访问该账号", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return ResponseEntity.ok(Result.success(account));
    }

    /** 创建新账号 */
    @PostMapping("/api/webdav/accounts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> createAccount(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CreateWebdavAccountRequest request) {
        Long userId = resolveUserId(authHeader);
        WebdavAccountResponse data = webdavAccountService.create(userId, request);
        return ResponseEntity.ok(Result.success(data));
    }

    /** 更新账号 */
    @PutMapping("/api/webdav/accounts/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WebdavAccountResponse>> updateAccount(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long accountId,
            @Valid @RequestBody UpdateWebdavAccountRequest request) {
        Long userId = resolveUserId(authHeader);
        WebdavAccountResponse existing = webdavAccountService.getById(accountId);
        if (!existing.getUserId().equals(userId)) {
            throw new BusinessException("40003", "无权操作该账号", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        WebdavAccountResponse data = webdavAccountService.update(accountId, request);
        return ResponseEntity.ok(Result.success(data));
    }

    /** 删除账号 */
    @DeleteMapping("/api/webdav/accounts/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> deleteAccount(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long accountId) {
        Long userId = resolveUserId(authHeader);
        WebdavAccountResponse existing = webdavAccountService.getById(accountId);
        if (!existing.getUserId().equals(userId)) {
            throw new BusinessException("40003", "无权操作该账号", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        webdavAccountService.delete(accountId);
        return ResponseEntity.ok(Result.success(null));
    }

    /** 设为默认账号 */
    @PutMapping("/api/webdav/accounts/{id}/default")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> setDefaultAccount(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long accountId) {
        Long userId = resolveUserId(authHeader);
        WebdavAccountResponse existing = webdavAccountService.getById(accountId);
        if (!existing.getUserId().equals(userId)) {
            throw new BusinessException("40003", "无权操作该账号", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        webdavAccountService.setDefault(userId, accountId);
        return ResponseEntity.ok(Result.success(null));
    }

    private Long resolveUserId(String authHeader) {
        return jwtUtils.getUserIdFromToken(extractToken(authHeader));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }
}
