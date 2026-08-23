package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrincipalValidatorTest {

    @Test
    void shouldUseOnlyLegacyValidatorInDefaultMode() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        GatewayProperties properties = properties(GatewayProperties.IdentityMode.LEGACY);
        when(restTemplate.postForObject(eq("http://mytools/internal/v1/gateway/tokens/validate"),
                any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class)))
                .thenReturn(new PrincipalValidator.ValidationResponse(true, 7L, "legacy", List.of("USER")));

        var principal = new PrincipalValidator(restTemplate, properties).validate("token");

        assertThat(principal.userId()).isEqualTo(7L);
        verify(restTemplate, never()).postForObject(eq("http://identity/internal/v1/identity/tokens/validate"),
                any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class));
    }

    @Test
    void shouldFallbackToIdentityOnlyAfterLegacyReturnsInactiveInDualMode() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        GatewayProperties properties = properties(GatewayProperties.IdentityMode.DUAL);
        when(restTemplate.postForObject(eq("http://mytools/internal/v1/gateway/tokens/validate"),
                any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class)))
                .thenReturn(new PrincipalValidator.ValidationResponse(false, null, null, List.of()));
        when(restTemplate.postForObject(eq("http://identity/internal/v1/identity/tokens/validate"),
                any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class)))
                .thenReturn(new PrincipalValidator.ValidationResponse(true, 8L, "identity", List.of("USER")));

        var principal = new PrincipalValidator(restTemplate, properties).validate("token");

        assertThat(principal.userId()).isEqualTo(8L);
    }

    @Test
    void shouldFailClosedWhenValidationResponseIsMissing() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class),
                eq(PrincipalValidator.ValidationResponse.class))).thenReturn(null);

        assertThatThrownBy(() -> new PrincipalValidator(restTemplate,
                properties(GatewayProperties.IdentityMode.IDENTITY)).validate("token"))
                .isInstanceOf(GatewayUnauthorizedException.class);
    }

    private GatewayProperties properties(GatewayProperties.IdentityMode mode) {
        return new GatewayProperties(mode, true, Set.of(7L, 8L), "http://mytools", "http://identity", "http://reader",
                "gateway-token", "identity-token", "reader-token", 1000, 3000);
    }
}
