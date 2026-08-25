package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.LoginRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.RefreshRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.TokenPair;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.IdentityGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdentityGatewayControllerTest {
    @Test
    void shouldForwardValidatedLoginAndRefreshWhenEnabled() {
        IdentityGatewayClient client = mock(IdentityGatewayClient.class);
        IdentityGatewayController controller = new IdentityGatewayController(properties(true), client);
        LoginRequest login = new LoginRequest("user", "secret", "web");
        RefreshRequest refresh = new RefreshRequest("refresh-token");
        TokenPair pair = pair();
        when(client.login(login, "correlation")).thenReturn(pair);
        when(client.refresh(refresh, "correlation")).thenReturn(pair);

        assertThat(controller.login(login, request())).isEqualTo(pair);
        assertThat(controller.refresh(refresh, request())).isEqualTo(pair);
    }

    @Test
    void shouldNotCallIdentityWhenRouteIsDisabled() {
        IdentityGatewayClient client = mock(IdentityGatewayClient.class);
        IdentityGatewayController controller = new IdentityGatewayController(properties(false), client);
        LoginRequest login = new LoginRequest("user", "secret", "web");
        RefreshRequest refresh = new RefreshRequest("refresh-token");

        assertThatThrownBy(() -> controller.login(login, request()))
                .isInstanceOf(GatewayRouteDisabledException.class);
        assertThatThrownBy(() -> controller.refresh(refresh, request()))
                .isInstanceOf(GatewayRouteDisabledException.class);
        verify(client, never()).login(login, "correlation");
        verify(client, never()).refresh(refresh, "correlation");
    }

    @Test
    void shouldNotIssueIdentityTokenWhileValidationStillUsesLegacyOnly() {
        IdentityGatewayClient client = mock(IdentityGatewayClient.class);
        GatewayProperties legacyOnly = properties(true, GatewayProperties.IdentityMode.LEGACY);
        IdentityGatewayController controller = new IdentityGatewayController(legacyOnly, client);
        LoginRequest login = new LoginRequest("user", "secret", "web");

        assertThatThrownBy(() -> controller.login(login, request()))
                .isInstanceOf(GatewayRouteDisabledException.class);
        verify(client, never()).login(login, "correlation");
    }

    @Test
    void shouldRevokeOnlySessionFromValidatedPrincipal() {
        IdentityGatewayClient client = mock(IdentityGatewayClient.class);
        IdentityGatewayController controller = new IdentityGatewayController(properties(true), client);
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        MockHttpServletRequest request = request();
        request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,
                new GatewayPrincipal(55L, "user", java.util.List.of("USER"), sessionId));

        controller.logout(request);

        verify(client).logout(sessionId, "correlation");
    }

    @Test
    void shouldRejectLogoutWithoutValidatedIdentitySession() {
        IdentityGatewayClient client = mock(IdentityGatewayClient.class);
        IdentityGatewayController controller = new IdentityGatewayController(properties(true), client);
        MockHttpServletRequest request = request();
        request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,
                new GatewayPrincipal(55L, "legacy", java.util.List.of("USER"), null));

        assertThatThrownBy(() -> controller.logout(request))
                .isInstanceOf(com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException.class);
        verify(client, never()).logout(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRejectInvalidOrUnknownLoginFieldsWithStableError() throws Exception {
        IdentityGatewayClient client = mock(IdentityGatewayClient.class);
        IdentityGatewayController controller = new IdentityGatewayController(properties(true), client);
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GatewayExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
                .build();

        mvc.perform(post("/api/app/v1/identity/login")
                        .requestAttr(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation")
                        .contentType("application/json")
                        .content("""
                                {"username":"bad user","password":"secret","deviceId":"web"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GATEWAY_003"));
        mvc.perform(post("/api/app/v1/identity/login")
                        .requestAttr(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation")
                        .contentType("application/json")
                        .content("""
                                {"username":"user","password":"secret","deviceId":"web","internalToken":"x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GATEWAY_003"));
        verify(client, never()).login(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation");
        return request;
    }

    private TokenPair pair() {
        return new TokenPair("access", "refresh", "Bearer", 300, 3600, UUID.randomUUID(),
                55L, "tester", java.util.List.of("USER"));
    }

    private GatewayProperties properties(boolean enabled) {
        return properties(enabled, GatewayProperties.IdentityMode.DUAL);
    }

    private GatewayProperties properties(boolean enabled, GatewayProperties.IdentityMode mode) {
        return new GatewayProperties(mode, enabled, false, Set.of(),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity", "http://reader", "http://drive",
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
