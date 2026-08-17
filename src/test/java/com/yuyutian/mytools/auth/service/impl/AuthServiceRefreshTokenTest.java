package com.yuyutian.mytools.auth.service.impl;

import com.yuyutian.mytools.auth.Model.RefreshResponse;
import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.service.RegistrationCodeService;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.cloudfile.service.MediaPlaybackTicketService;
import com.yuyutian.mytools.user.mapper.LoginAttemptMapper;
import com.yuyutian.mytools.user.mapper.RoleFinderMapper;
import com.yuyutian.mytools.user.mapper.UserMapper;
import com.yuyutian.mytools.user.mapper.UserRoleMapper;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刷新令牌单次轮换回归测试。
 */
class AuthServiceRefreshTokenTest {

    /**
     * 有效刷新令牌应同时轮换访问令牌和刷新令牌。
     */
    @Test
    void refreshRotatesBothTokens() {
        TestContext context = createContext();
        when(context.tokenMapper.update(context.token)).thenReturn(1);

        RefreshResponse response = context.service.refreshToken("Bearer old-refresh");

        assertEquals("new-access", response.getAccessToken());
        assertEquals("new-refresh", response.getRefreshToken());
        assertEquals("new-access", context.token.getAccessToken());
        assertEquals("new-refresh", context.token.getRefreshToken());
        verify(context.tokenMapper).update(context.token);
    }

    /**
     * 乐观锁冲突表示旧刷新令牌已经被消费，必须拒绝重放。
     */
    @Test
    void refreshRejectsConsumedToken() {
        TestContext context = createContext();
        when(context.tokenMapper.update(context.token)).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> context.service.refreshToken("Bearer old-refresh"));
    }

    /**
     * 访问令牌不得用于刷新会话。
     */
    @Test
    void refreshRejectsAccessTokenType() {
        TestContext context = createContext();
        when(context.jwtUtils.getTokenTypeFromToken("old-refresh")).thenReturn("access");

        assertThrows(BusinessException.class,
                () -> context.service.refreshToken("Bearer old-refresh"));
    }

    /**
     * 登出应同时撤销当前登录会话签发的播放票据。
     */
    @Test
    void logoutRevokesPlaybackTicketsForCurrentSession() {
        TestContext context = createContext();
        context.token.setAccessToken("active-access");
        when(context.jwtUtils.validateToken("active-access")).thenReturn(true);
        when(context.jwtUtils.getUserIdFromToken("active-access")).thenReturn(42L);
        when(context.tokenMapper.findByAccessToken("active-access")).thenReturn(context.token);

        context.service.logout("Bearer active-access");

        verify(context.tokenMapper).invalidateByAccessToken("active-access");
        verify(context.mediaPlaybackTicketService).revokeSession(10L);
    }

    private TestContext createContext() {
        TokenMapper tokenMapper = mock(TokenMapper.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        MediaPlaybackTicketService mediaPlaybackTicketService = mock(MediaPlaybackTicketService.class);
        AuthServiceImpl service = new AuthServiceImpl(
                mock(UserMapper.class), mock(RoleFinderMapper.class), mock(UserRoleMapper.class),
                tokenMapper, mock(LoginAttemptMapper.class), jwtUtils,
                mock(SnowflakeIdGenerator.class), mock(RegistrationCodeService.class),
                mediaPlaybackTicketService);
        Token token = new Token();
        token.setId(10L);
        token.setUserId(42L);
        token.setRefreshToken("old-refresh");
        token.setRefreshExpireTime(System.currentTimeMillis() + 60000);
        token.setVersion(3);
        token.setStatus("ACTIVE");
        when(jwtUtils.validateToken("old-refresh")).thenReturn(true);
        when(jwtUtils.getTokenTypeFromToken("old-refresh")).thenReturn("refresh");
        when(jwtUtils.getUserIdFromToken("old-refresh")).thenReturn(42L);
        when(jwtUtils.getUsernameFromToken("old-refresh")).thenReturn("reader");
        when(jwtUtils.getRoleFromToken("old-refresh")).thenReturn("USER");
        when(jwtUtils.generateAccessToken(42L, "reader", "USER")).thenReturn("new-access");
        when(jwtUtils.generateRefreshToken(42L, "reader", "USER")).thenReturn("new-refresh");
        when(jwtUtils.getExpirationMs()).thenReturn(900000L);
        when(jwtUtils.getRefreshExpirationMs()).thenReturn(604800000L);
        when(tokenMapper.findByRefreshToken("old-refresh")).thenReturn(token);
        return new TestContext(service, tokenMapper, jwtUtils, token, mediaPlaybackTicketService);
    }

    private record TestContext(AuthServiceImpl service, TokenMapper tokenMapper,
                               JwtUtils jwtUtils, Token token,
                               MediaPlaybackTicketService mediaPlaybackTicketService) {
    }
}
