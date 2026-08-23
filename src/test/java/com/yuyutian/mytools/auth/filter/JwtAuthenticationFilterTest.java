package com.yuyutian.mytools.auth.filter;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.auth.identity.IdentityValidationGateway;
import com.yuyutian.mytools.auth.identity.IdentityValidationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JWT请求头鉴权边界回归测试。
 */
class JwtAuthenticationFilterTest {

    /**
     * 清理测试认证上下文。
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 播放端点不得读取查询参数中的访问令牌。
     */
    @Test
    void playEndpointRejectsAccessTokenQueryParameter() throws Exception {
        JwtUtils jwtUtils = mock(JwtUtils.class);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/localfiles/42/play");
        request.setParameter("access_token", "video-token");
        TokenMapper tokenMapper = mock(TokenMapper.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtils, tokenMapper);

        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        verifyNoInteractions(jwtUtils);
        verifyNoInteractions(tokenMapper);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 数据库中已撤销的访问令牌不得建立认证上下文。
     */
    @Test
    void revokedAccessTokenIsRejected() throws Exception {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        TokenMapper tokenMapper = mock(TokenMapper.class);
        when(jwtUtils.validateToken("revoked-token")).thenReturn(true);
        when(jwtUtils.getTokenTypeFromToken("revoked-token")).thenReturn("access");
        when(jwtUtils.getUserIdFromToken("revoked-token")).thenReturn(42L);
        Token token = new Token();
        token.setUserId(42L);
        token.setStatus("INVALID");
        token.setExpireTime(System.currentTimeMillis() + 60000);
        when(tokenMapper.findByAccessToken("revoked-token")).thenReturn(token);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer revoked-token");

        new JwtAuthenticationFilter(jwtUtils, tokenMapper).doFilterInternal(
                request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(request.getAttribute("jwtAuthenticationFailed"));
    }

    /**
     * Dual 模式应在旧 JWT 失败后接受 Identity 会话。
     */
    @Test
    void dualModeFallsBackToIdentityValidation() throws Exception {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        TokenMapper tokenMapper = mock(TokenMapper.class);
        IdentityValidationGateway gateway = mock(IdentityValidationGateway.class);
        IdentityValidationProperties properties = new IdentityValidationProperties();
        properties.setMode(IdentityValidationProperties.Mode.DUAL);
        UUID sessionId = UUID.randomUUID();
        when(gateway.validate("identity-token")).thenReturn(new IdentityValidationGateway.Principal(
                true, 77L, "identity-user", List.of("USER"), sessionId, Instant.now().plusSeconds(600)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer identity-token");

        new JwtAuthenticationFilter(jwtUtils, tokenMapper, gateway, properties).doFilterInternal(
                request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        org.junit.jupiter.api.Assertions.assertEquals(77L, request.getAttribute("userId"));
        org.junit.jupiter.api.Assertions.assertEquals(sessionId, request.getAttribute("identitySessionId"));
    }
}
