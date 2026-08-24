package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.ReaderGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderGatewayControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldOverwriteOwnerWithValidatedPrincipal() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        MockHttpServletRequest request = request(55L);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        when(client.save(eq("shelves"), payload.capture(), eq("correlation")))
                .thenReturn(Map.of("ownerId", 55L));

        var result = controller.saveShelf(new ReaderGatewayController.ShelfRequest(
                "book", Map.of("title", "Book"), false, null), request);

        assertThat(result).containsEntry("ownerId", 55L);
        assertThat(payload.getValue()).containsEntry("ownerId", 55L);
        assertThat(payload.getValue()).doesNotContainKey("authorization");
    }

    @Test
    void shouldNotCallDownstreamWhenRouteIsDisabled() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(false), client);

        assertThatThrownBy(() -> controller.shelves(false, request(55L)))
                .isInstanceOf(GatewayRouteDisabledException.class);
        verify(client, never()).list(eq("shelves"), eq(55L), eq(false), eq("correlation"));
    }

    @Test
    void shouldNotCallDownstreamWhenPrincipalIsOutsideAllowlist() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);

        assertThatThrownBy(() -> controller.shelves(false, request(56L)))
                .isInstanceOf(GatewayRouteDisabledException.class);
        verify(client, never()).list(eq("shelves"), eq(56L), eq(false), eq("correlation"));
    }

    private MockHttpServletRequest request(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,
                new GatewayPrincipal(userId, "user", List.of("USER"), null));
        request.setAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation");
        return request;
    }

    private GatewayProperties properties(boolean enabled) {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, enabled, Set.of(55L),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity", "http://reader", "http://drive",
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "");
    }
}
