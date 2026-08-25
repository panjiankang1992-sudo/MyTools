package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;
import java.util.List;

/**
 * Gateway 对外认证契约。
 */
public final class IdentityGatewayModels {
    private IdentityGatewayModels() {
    }

    /**
     * 登录请求。
     */
    public record LoginRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String username,
            @NotBlank @Size(max = 1024) String password,
            @NotBlank @Size(max = 255) String deviceId) {
    }

    /**
     * 刷新令牌请求。
     */
    public record RefreshRequest(@NotBlank @Size(max = 1024) String refreshToken) {
    }

    /**
     * Identity 令牌对响应。
     */
    public record TokenPair(String accessToken, String refreshToken, String tokenType,
                            long expiresIn, long refreshExpiresIn, UUID sessionId,
                            long userId, String username, List<String> roles) {
    }

    /**
     * 当前登录身份。
     */
    public record CurrentIdentity(long userId, String username, String nickname, String avatar, String role) {
    }
}
