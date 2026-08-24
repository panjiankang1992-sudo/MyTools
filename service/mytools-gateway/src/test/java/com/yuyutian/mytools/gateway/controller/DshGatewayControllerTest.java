package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.DshGatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.DshGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DshGatewayControllerTest {
    @Test
    void shouldInjectTrustedOwnerForSessionLifecycle() {
        DshGatewayClient client = mock(DshGatewayClient.class);
        when(client.list(55L, "correlation")).thenReturn(List.of());
        DshGatewayController controller = new DshGatewayController(properties(), client);
        MockHttpServletRequest request = request();

        assertThat(controller.list(request)).isEmpty();
        controller.archive("session-1", request);
        verify(client).list(55L, "correlation");
        verify(client).archive("session-1", 55L, "correlation");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,
                new GatewayPrincipal(55L, "user", List.of("USER"), null));
        request.setAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation");
        return request;
    }

    private DshGatewayProperties properties() {
        return new DshGatewayProperties(true, "", "token");
    }
}
