package com.yuyutian.mytools.token.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.token.model.TokenInfo;
import com.yuyutian.mytools.token.model.TokenPageResponse;
import com.yuyutian.mytools.token.service.TokenManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Token管理 Controller。
 * 提供令牌列表查看、在线状态查看、强制下线等能力。
 *
 * @author mytools
 * @since 2026-05-04
 */
@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final TokenManagementService tokenManagementService;
    private final JwtUtils jwtUtils;

    /**
     * 获取当前用户的令牌列表。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<TokenPageResponse>> getMyTokens(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        TokenPageResponse response = tokenManagementService.getUserTokens(userId);
        return ResponseEntity.ok(Result.success(response));
    }

    /**
     * 获取当前令牌信息。
     */
    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<TokenInfo>> getCurrentToken(
            @RequestHeader("Authorization") String authHeader) {
        TokenInfo tokenInfo = tokenManagementService.getCurrentToken(authHeader);
        if (tokenInfo == null) {
            return ResponseEntity.ok(Result.error("AUTH_002", "令牌无效"));
        }
        return ResponseEntity.ok(Result.success(tokenInfo));
    }

    /**
     * 获取用户在线令牌数量。
     */
    @GetMapping("/online-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Map<String, Object>>> getOnlineCount(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        int count = tokenManagementService.getOnlineTokenCount(userId);

        Map<String, Object> data = new HashMap<>();
        data.put("count", count);
        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 使指定令牌失效（强制下线）。
     */
    @DeleteMapping("/{tokenId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> invalidateToken(
            @PathVariable Long tokenId,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        tokenManagementService.invalidateToken(tokenId, userId);
        return ResponseEntity.ok(Result.success("令牌已失效"));
    }

    /**
     * 使当前用户所有其他令牌失效（除当前令牌外）。
     */
    @DeleteMapping("/others")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> invalidateOtherTokens(
            @RequestHeader("Authorization") String authHeader) {
        String token = extractToken(authHeader);
        Long userId = jwtUtils.getUserIdFromToken(token);

        // 获取当前令牌ID（简化处理，实际应从token中解析）
        TokenInfo currentToken = tokenManagementService.getCurrentToken(authHeader);
        if (currentToken != null) {
            tokenManagementService.invalidateOtherTokens(currentToken.getId(), userId);
        } else {
            tokenManagementService.invalidateOtherTokens(null, userId);
        }
        return ResponseEntity.ok(Result.success("其他令牌已全部失效"));
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
