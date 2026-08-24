package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.AppCatalogGatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.AppCatalogGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppCatalogGatewayControllerTest {
    @Test
    void shouldInjectTrustedOwner() {
        AppCatalogGatewayClient client = mock(AppCatalogGatewayClient.class);
        when(client.list("correlation")).thenReturn(List.of());
        AppCatalogGatewayController controller = new AppCatalogGatewayController(properties(), client);

        assertThat(controller.list(request())).isEmpty();
        verify(client).list("correlation");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,
                new GatewayPrincipal(55L, "user", List.of("USER"), null));
        request.setAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation");
        return request;
    }

    private AppCatalogGatewayProperties properties() {
        return new AppCatalogGatewayProperties(true, "", "token");
    }
}
