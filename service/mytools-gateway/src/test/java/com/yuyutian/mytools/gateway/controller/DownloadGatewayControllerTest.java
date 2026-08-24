package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.CreateHttpDownload;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.DownloadView;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.DownloadGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayBadRequestException;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadGatewayControllerTest {
    @Test
    void shouldInjectTrustedOwnerIntoHttpDownload() {
        DownloadGatewayClient client = mock(DownloadGatewayClient.class);
        DownloadGatewayController controller = new DownloadGatewayController(properties(true), client);
        CreateHttpDownload body = new CreateHttpDownload("asset-1", "https://example.org/file",
                "file.bin", 1024L);
        DownloadView expected = new DownloadView(UUID.randomUUID(), "PENDING", null, null);
        when(client.createHttp(body, 55L, "correlation")).thenReturn(expected);

        assertThat(controller.createHttp(body, request(55L))).isEqualTo(expected);
        verify(client).createHttp(body, 55L, "correlation");
    }

    @Test
    void shouldRejectUnsafeSourceBeforeCallingDownstream() {
        DownloadGatewayClient client = mock(DownloadGatewayClient.class);
        DownloadGatewayController controller = new DownloadGatewayController(properties(true), client);
        CreateHttpDownload body = new CreateHttpDownload("asset-1", "http://example.org/file",
                "../file.bin", 1024L);

        assertThatThrownBy(() -> controller.createHttp(body, request(55L)))
                .isInstanceOf(GatewayBadRequestException.class);
        verify(client, never()).createHttp(body, 55L, "correlation");
    }

    @Test
    void shouldNotCallDownloadWhenDisabledOrTenantIsNotAllowlisted() {
        DownloadGatewayClient client = mock(DownloadGatewayClient.class);
        CreateHttpDownload body = new CreateHttpDownload("asset-1", "https://example.org/file",
                "file.bin", 1024L);

        assertThatThrownBy(() -> new DownloadGatewayController(properties(false), client)
                .createHttp(body, request(55L))).isInstanceOf(GatewayRouteDisabledException.class);
        assertThatThrownBy(() -> new DownloadGatewayController(properties(true), client)
                .createHttp(body, request(56L))).isInstanceOf(GatewayRouteDisabledException.class);
        verify(client, never()).createHttp(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
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
                false, Set.of(), enabled, Set.of(55L), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token", "identity-token",
                "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
