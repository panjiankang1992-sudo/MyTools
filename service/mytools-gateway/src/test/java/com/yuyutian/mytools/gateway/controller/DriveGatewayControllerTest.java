package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.DriveGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveGatewayControllerTest {
    private final UUID accountId = UUID.randomUUID();

    @Test
    void shouldInjectTrustedOwnerAndCorrelationIntoDriveQuery() {
        DriveGatewayClient client = mock(DriveGatewayClient.class);
        DriveGatewayController controller = new DriveGatewayController(properties(true), client);
        when(client.items(accountId, 55L, "books", "correlation"))
                .thenReturn(List.of(Map.of("name", "book.epub")));

        var result = controller.items(accountId, "books", request(55L));

        assertThat(result).containsExactly(Map.of("name", "book.epub"));
        verify(client).items(accountId, 55L, "books", "correlation");
    }

    @Test
    void shouldNotCallDriveWhenRouteIsDisabledOrPrincipalIsNotAllowlisted() {
        DriveGatewayClient client = mock(DriveGatewayClient.class);
        DriveGatewayController disabled = new DriveGatewayController(properties(false), client);
        DriveGatewayController enabled = new DriveGatewayController(properties(true), client);

        assertThatThrownBy(() -> disabled.items(accountId, "", request(55L)))
                .isInstanceOf(GatewayRouteDisabledException.class);
        assertThatThrownBy(() -> enabled.items(accountId, "", request(56L)))
                .isInstanceOf(GatewayRouteDisabledException.class);
        verify(client, never()).items(accountId, 55L, "", "correlation");
        verify(client, never()).items(accountId, 56L, "", "correlation");
    }

    private MockHttpServletRequest request(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,
                new GatewayPrincipal(userId, "user", List.of("USER"), null));
        request.setAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation");
        return request;
    }

    private GatewayProperties properties(boolean enabled) {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, false, Set.of(),
                enabled, Set.of(55L), false, Set.of(), "http://mytools", "http://identity", "http://reader", "http://drive",
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000);
    }
}
