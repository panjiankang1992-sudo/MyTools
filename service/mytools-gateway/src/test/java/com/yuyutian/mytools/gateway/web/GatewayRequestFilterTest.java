package com.yuyutian.mytools.gateway.web;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.PrincipalValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayRequestFilterTest {

    @Test
    void shouldNotValidateOrCallDownstreamWhenReaderRouteIsDisabled() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/shelves");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldReturnStableUnauthorizedResponseBeforeController() throws Exception {
        GatewayRequestFilter filter = new GatewayRequestFilter(mock(PrincipalValidator.class), properties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/shelves");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("GATEWAY_001");
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void shouldRejectAuthenticatedPrincipalOutsideAllowlist() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token")).thenReturn(new GatewayPrincipal(56L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/shelves");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("GATEWAY_002");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void shouldPassAllowlistedPrincipalToController() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token")).thenReturn(new GatewayPrincipal(55L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/shelves");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(55L, "user", List.of("USER"), null));
    }

    @Test
    void shouldNotValidateDriveRequestWhenDriveRouteIsDisabled() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false, false));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/app/v1/drive/accounts/00000000-0000-0000-0000-000000000001/items");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldInjectAllowlistedPrincipalForEnabledDriveRoute() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token")).thenReturn(new GatewayPrincipal(55L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false, true));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/app/v1/drive/accounts/00000000-0000-0000-0000-000000000001/items");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(55L, "user", List.of("USER"), null));
    }

    @Test
    void shouldInjectAllowlistedPrincipalForEnabledDownloadRoute() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token")).thenReturn(new GatewayPrincipal(55L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false, false, true));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/app/v1/downloads/http");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(55L, "user", List.of("USER"), null));
    }

    @Test
    void shouldAuthenticateIdentityLogoutWithoutApplyingTenantAllowlist() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        when(validator.validate("token"))
                .thenReturn(new GatewayPrincipal(99L, "user", List.of("USER"), sessionId));
        GatewayProperties properties = new GatewayProperties(GatewayProperties.IdentityMode.DUAL,
                true, false, Set.of(), false, Set.of(), false, Set.of(), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token", "identity-token",
                "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/app/v1/identity/logout");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(99L, "user", List.of("USER"), sessionId));
    }

    @Test
    void shouldAuthenticateEnabledMediaRouteWithoutTenantAllowlist() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token"))
                .thenReturn(new GatewayPrincipal(77L, "user", List.of("USER"), null));
        GatewayProperties properties = new GatewayProperties(GatewayProperties.IdentityMode.LEGACY,
                false, false, Set.of(), false, Set.of(), false, Set.of(), "http://mytools",
                "http://identity", "http://reader", "http://drive", "http://download",
                "gateway-token", "identity-token", "reader-token", "drive-token",
                "download-token", 1000, 3000, true, "http://media", "media-token", false, "", "");
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/media/items");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(77L, "user", List.of("USER"), null));
    }

    @Test
    void shouldAuthenticateEnabledMessagingRoute() throws Exception {
        PrincipalValidator validator=mock(PrincipalValidator.class);when(validator.validate("token")).thenReturn(new GatewayPrincipal(88L,"user",List.of("USER"),null));GatewayProperties properties=new GatewayProperties(GatewayProperties.IdentityMode.LEGACY,false,false,Set.of(),false,Set.of(),false,Set.of(),"http://mytools","http://identity","http://reader","http://drive","http://download","gateway-token","identity-token","reader-token","drive-token","download-token",1000,3000,false,"","",true,"http://messaging","messaging-token");GatewayRequestFilter filter=new GatewayRequestFilter(validator,properties);MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/app/v1/messages");request.addHeader("Authorization","Bearer token");MockFilterChain chain=new MockFilterChain();filter.doFilter(request,new MockHttpServletResponse(),chain);assertThat(chain.getRequest()).isNotNull();assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE)).isEqualTo(new GatewayPrincipal(88L,"user",List.of("USER"),null));
    }

    private GatewayProperties properties(boolean enabled) {
        return properties(enabled, false);
    }

    private GatewayProperties properties(boolean readerEnabled, boolean driveEnabled) {
        return properties(readerEnabled, driveEnabled, false);
    }

    private GatewayProperties properties(boolean readerEnabled, boolean driveEnabled, boolean downloadEnabled) {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, readerEnabled, Set.of(55L),
                driveEnabled, Set.of(55L), downloadEnabled, Set.of(55L), "http://mytools", "http://identity", "http://reader", "http://drive",
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
