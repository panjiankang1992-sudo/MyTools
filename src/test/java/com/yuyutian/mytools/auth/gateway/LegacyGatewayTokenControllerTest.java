package com.yuyutian.mytools.auth.gateway;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.token.service.TokenManagementService;
import com.yuyutian.mytools.user.Model.User;
import com.yuyutian.mytools.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyGatewayTokenControllerTest {

    @Test
    void shouldReturnOnlyActiveTokenAndUserPrincipal() {
        TokenManagementService tokenService = mock(TokenManagementService.class);
        UserMapper userMapper = mock(UserMapper.class);
        Token token = new Token();
        token.setUserId(9L);
        token.setStatus("ACTIVE");
        token.setExpireTime(System.currentTimeMillis() + 60_000);
        User user = new User();
        user.setId(9L);
        user.setUsername("reader");
        user.setRole("USER");
        user.setStatus("ACTIVE");
        when(tokenService.getTokenByAccessToken("access-token")).thenReturn(token);
        when(userMapper.findById(9L)).thenReturn(user);
        var controller = new LegacyGatewayTokenController(tokenService, userMapper, "gateway-token");

        var principal = controller.validate("Bearer gateway-token",
                new LegacyGatewayTokenController.ValidateRequest("access-token"));

        assertThat(principal.active()).isTrue();
        assertThat(principal.userId()).isEqualTo(9L);
        assertThat(principal.roles()).containsExactly("USER");
    }

    @Test
    void shouldFailClosedWhenInternalAuthorizationIsMissing() {
        var controller = new LegacyGatewayTokenController(mock(TokenManagementService.class),
                mock(UserMapper.class), "gateway-token");

        assertThatThrownBy(() -> controller.validate(null,
                new LegacyGatewayTokenController.ValidateRequest("access-token")))
                .isInstanceOf(SecurityException.class);
    }
}
