package com.yuyutian.mytools.token.controller;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.MessageHelper;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.token.model.TokenInfo;
import com.yuyutian.mytools.token.model.TokenPageResponse;
import com.yuyutian.mytools.token.service.TokenManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
@Slf4j
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
            return ResponseEntity.ok(Result.error(ErrorCode.AUTH_002));
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
     * 分页获取令牌列表。
     */
    @GetMapping("/list")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<TokenPageResponse>> getTokenPage(
            @RequestParam int page,
            @RequestParam int pageSize,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        TokenPageResponse response = tokenManagementService.getTokenPage(userId, page, pageSize);
        return ResponseEntity.ok(Result.success(response));
    }

    /**
     * 创建新令牌。
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<TokenInfo>> createToken(
            @RequestBody Map<String, String> body,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        String tokenName = body.get("tokenName");
        TokenInfo tokenInfo = tokenManagementService.createToken(userId, tokenName);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(MessageHelper.getMessage("success.token.create"), tokenInfo));
    }

    /**
     * 获取令牌详情。
     */
    @GetMapping("/{tokenId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<TokenInfo>> getTokenDetail(
            @PathVariable Long tokenId,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        TokenInfo tokenInfo = tokenManagementService.getCurrentToken("Bearer " + jwtUtils.getUserIdFromToken(extractToken(authHeader)));
        // 简化实现，返回当前用户令牌列表中的第一个匹配的
        TokenPageResponse tokens = tokenManagementService.getTokenPage(userId, 1, 100);
        TokenInfo found = tokens.getList().stream()
                .filter(t -> t.getId().equals(tokenId))
                .findFirst()
                .orElse(null);
        if (found != null) {
            return ResponseEntity.ok(Result.success(found));
        }
        return ResponseEntity.ok(Result.error(ErrorCode.TOKEN_001));
    }

    /**
     * 更新令牌状态。
     */
    @PutMapping("/{tokenId}/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> updateTokenStatus(
            @PathVariable Long tokenId,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        Object statusObj = body.get("status");
        String status;
        if (statusObj instanceof Number) {
            status = ((Number) statusObj).intValue() == 1 ? "ACTIVE" : "INVALID";
        } else if (statusObj instanceof String) {
            String strStatus = (String) statusObj;
            if ("ACTIVE".equalsIgnoreCase(strStatus) || "1".equals(strStatus)) {
                status = "ACTIVE";
            } else {
                status = "INVALID";
            }
        } else {
            status = "INVALID";
        }
        tokenManagementService.updateTokenStatus(tokenId, status, userId);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.update"), null));
    }

    /**
     * 删除令牌。
     */
    @DeleteMapping("/{tokenId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> deleteToken(
            @PathVariable Long tokenId,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        tokenManagementService.deleteToken(tokenId, userId);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.delete"), null));
    }

    /**
     * 验证令牌。
     */
    @PostMapping("/validate")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Result<Map<String, Object>>> validateToken(
            @RequestParam String tokenValue) {
        // 处理 Bearer 前缀
        String actualToken = tokenValue;
        if (tokenValue.startsWith("Bearer ")) {
            actualToken = tokenValue.substring(7);
        }

        log.info("校验Token: {}", actualToken);
        Token token = tokenManagementService.getTokenByAccessToken(actualToken);
        log.info("查询到Token: {}", token);
        Map<String, Object> result = new HashMap<>();

        if (token != null && "ACTIVE".equals(token.getStatus()) && token.getExpireTime() > System.currentTimeMillis()) {
            result.put("valid", true);
            result.put("userId", token.getUserId());
            result.put("username", null);
        } else {
            result.put("valid", false);
            if (token == null) {
                result.put("message", "Token 不存在");
            } else if (!"ACTIVE".equals(token.getStatus())) {
                result.put("message", "Token 状态为: " + token.getStatus());
            } else {
                result.put("message", "Token 已过期");
            }
        }
        return ResponseEntity.ok(Result.success(result));
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
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.other.tokens.invalidated"), null));
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
