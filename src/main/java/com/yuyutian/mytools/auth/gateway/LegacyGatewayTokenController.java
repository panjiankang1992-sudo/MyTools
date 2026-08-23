package com.yuyutian.mytools.auth.gateway;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.token.service.TokenManagementService;
import com.yuyutian.mytools.user.Model.User;
import com.yuyutian.mytools.user.mapper.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Gateway 使用的旧会话内部校验接口。
 */
@RestController
@RequestMapping("/internal/v1/gateway/tokens")
public class LegacyGatewayTokenController {

    private final TokenManagementService tokenService;
    private final UserMapper userMapper;
    private final String internalToken;

    /**
     * 创建旧会话内部校验控制器。
     */
    public LegacyGatewayTokenController(TokenManagementService tokenService, UserMapper userMapper,
                                        @Value("${migration.gateway.internal-token:}") String internalToken) {
        this.tokenService = tokenService;
        this.userMapper = userMapper;
        this.internalToken = internalToken;
    }

    /**
     * 校验旧访问令牌及当前用户状态。
     */
    @PostMapping("/validate")
    public PrincipalResponse validate(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody ValidateRequest request) {
        authorize(authorization);
        Token token = tokenService.getTokenByAccessToken(request.accessToken());
        if (token == null || !"ACTIVE".equals(token.getStatus())
                || token.getExpireTime() == null || token.getExpireTime() <= System.currentTimeMillis()) {
            return PrincipalResponse.inactive();
        }
        User user = userMapper.findById(token.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return PrincipalResponse.inactive();
        }
        String role = user.getRole() == null || user.getRole().isBlank() ? "USER" : user.getRole();
        return new PrincipalResponse(true, user.getId(), user.getUsername(), List.of(role));
    }

    private void authorize(String authorization) {
        byte[] expected = ("Bearer " + internalToken).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (internalToken.isBlank() || !MessageDigest.isEqual(expected, supplied)) {
            throw new SecurityException("Gateway internal authorization failed");
        }
    }

    /**
     * 旧访问令牌校验请求。
     */
    public record ValidateRequest(@NotBlank String accessToken) {
    }

    /**
     * Gateway 统一主体响应。
     */
    public record PrincipalResponse(boolean active, Long userId, String username, List<String> roles) {

        /**
         * 创建不包含用户信息的未激活主体。
         */
        public static PrincipalResponse inactive() {
            return new PrincipalResponse(false, null, null, List.of());
        }
    }
}
