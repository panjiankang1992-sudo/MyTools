package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.OperationView;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.RefreshIndexRequest;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.AccountSummary;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.CopyObjectRequest;
import com.yuyutian.mytools.gateway.service.DriveGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

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
        AccountSummary account=new AccountSummary(accountId,"Primary","RCLONE",true,true,3);
        when(client.accounts(55L,"correlation")).thenReturn(List.of(account));
        assertThat(controller.accounts(request(55L))).containsExactly(account);
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

    @Test
    void shouldInjectTrustedOwnerIntoRefreshLifecycle() {
        DriveGatewayClient client=mock(DriveGatewayClient.class);
        DriveGatewayController controller=new DriveGatewayController(properties(true),client);
        UUID operationId=UUID.randomUUID();
        OperationView operation=new OperationView(operationId,accountId,"INDEX_ACCOUNT",
            "RUNNING",null,Instant.EPOCH,Instant.EPOCH);
        RefreshIndexRequest body=new RefreshIndexRequest("refresh-1");
        when(client.refreshIndex(accountId,55L,body,"correlation")).thenReturn(operation);
        when(client.operation(operationId,55L,"correlation")).thenReturn(operation);
        when(client.cancel(operationId,55L,"correlation")).thenReturn(operation);

        assertThat(controller.refreshIndex(accountId,body,request(55L))).isEqualTo(operation);
        assertThat(controller.operation(operationId,request(55L))).isEqualTo(operation);
        assertThat(controller.cancel(operationId,request(55L))).isEqualTo(operation);
    }

    @Test
    void shouldInjectTrustedOwnerIntoCopyOperation() {
        DriveGatewayClient client = mock(DriveGatewayClient.class);
        DriveGatewayController controller = new DriveGatewayController(properties(true), client);
        UUID targetAccountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        CopyObjectRequest body = new CopyObjectRequest("copy-1", targetAccountId, "a.bin", "b.bin");
        OperationView operation = new OperationView(operationId, accountId, "COPY_OBJECT",
                "RUNNING", null, Instant.EPOCH, Instant.EPOCH);
        when(client.copyObject(accountId, 55L, body, "correlation")).thenReturn(operation);

        assertThat(controller.copyObject(accountId, body, request(55L))).isEqualTo(operation);
        verify(client).copyObject(accountId, 55L, body, "correlation");
    }

    @Test
    void shouldInjectTrustedOwnerIntoTreeCopyOperation() {
        DriveGatewayClient client = mock(DriveGatewayClient.class);
        DriveGatewayController controller = new DriveGatewayController(properties(true), client);
        UUID targetAccountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        var body = new com.yuyutian.mytools.gateway.model.DriveGatewayModels.CopyTreeRequest(
                "tree-1", targetAccountId, "books", "backup", 5000);
        OperationView operation = new OperationView(operationId, accountId, "COPY_TREE_NATIVE",
                "RUNNING", null, Instant.EPOCH, Instant.EPOCH);
        when(client.copyTree(accountId, 55L, body, "correlation")).thenReturn(operation);

        assertThat(controller.copyTree(accountId, body, request(55L))).isEqualTo(operation);
        verify(client).copyTree(accountId, 55L, body, "correlation");
    }

    @Test
    void shouldInjectTrustedOwnerIntoTreeMoveOperation() {
        DriveGatewayClient client = mock(DriveGatewayClient.class);
        DriveGatewayController controller = new DriveGatewayController(properties(true), client);
        UUID targetAccountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        var body = new com.yuyutian.mytools.gateway.model.DriveGatewayModels.MoveTreeRequest(
                "move-1", targetAccountId, "incoming", "library", 10000);
        OperationView operation = new OperationView(operationId, accountId, "MOVE_TREE",
                "RUNNING", null, Instant.EPOCH, Instant.EPOCH);
        when(client.moveTree(accountId, 55L, body, "correlation")).thenReturn(operation);

        assertThat(controller.moveTree(accountId, body, request(55L))).isEqualTo(operation);
        verify(client).moveTree(accountId, 55L, body, "correlation");
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
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
