package com.yuyutian.mytools.gateway.web;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.service.PrincipalValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    private GatewayProperties properties(boolean enabled) {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, enabled, "http://mytools",
                "http://identity", "http://reader", "gateway-token", "identity-token", "reader-token",
                1000, 3000);
    }
}
