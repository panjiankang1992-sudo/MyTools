package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaPage;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaCatalogPage;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.StartDirectoryScan;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.OperationView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.StartAnalysis;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.MediaGatewayClient;
import com.yuyutian.mytools.gateway.service.MediaPlaybackTicketService;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaGatewayControllerTest {
    @Test
    void adaptiveTicketBindsEveryPartToTheSameOwnerAndMedia() {
        var client = mock(MediaGatewayClient.class);
        var control = controller(true, client);
        var req = request(55L);
        var id = UUID.randomUUID();
        var media = new com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaView(id,55L,UUID.randomUUID(),
                "sample","video/mp4",200L*1024*1024,"a".repeat(64),"READY",1,List.of(),"","");
        when(client.view(55L,id,"correlation")).thenReturn(media);
        var original = control.playTicket(id,req);
        assertThat(original.get("streamPath")).isEqualTo("/api/app/v1/media/tickets/" + original.get("ticket"));
        req.setParameter("adaptive","1");
        when(client.adaptiveEntry(55L,id,"correlation")).thenReturn("/master.m3u8");
        var adaptive = control.playTicket(id,req);
        String ticket = adaptive.get("ticket").toString();
        assertThat(adaptive.get("streamPath")).isEqualTo("/api/app/v1/media/tickets/" + ticket + "/master.m3u8");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        control.part(ticket,"v480-00001.ts",req,response);
        verify(client).streamPart(55L,id,"v480-00001.ts",null,response,"correlation");
        assertThatThrownBy(() -> control.part("0".repeat(32),"v480-00001.ts",req,response))
                .isInstanceOf(com.yuyutian.mytools.gateway.service.GatewayNotFoundException.class);
    }

    @Test
    void shouldBindOwnerAndSizeForImagePreview() {
        MediaGatewayClient client = mock(MediaGatewayClient.class);
        UUID id = UUID.randomUUID();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        controller(true, client).thumbnail(id, 2048, request(55L), response);
        verify(client).streamPreview(55L, id, 2048, response, "correlation");
    }
    @Test
    void shouldBindValidatedOwnerWhenListingMedia() {
        MediaGatewayClient client = mock(MediaGatewayClient.class);
        MediaGatewayController controller = controller(true, client);
        when(client.list(55L, null, false, null, 50, "correlation"))
                .thenReturn(new MediaPage(List.of(), null));

        MediaPage result = controller.list(null, false, null, 50, request(55L));

        assertThat(result.items()).isEmpty();
        verify(client).list(55L, null, false, null, 50, "correlation");
    }

    @Test
    void shouldNotCallMediaLibraryWhenRouteIsDisabled() {
        MediaGatewayClient client = mock(MediaGatewayClient.class);
        MediaGatewayController controller = controller(false, client);

        assertThatThrownBy(() -> controller.list(null, false, null, 50, request(55L)))
                .isInstanceOf(GatewayRouteDisabledException.class);
        verify(client, never()).list(55L, null, false, null, 50, "correlation");
    }

    @Test
    void shouldBindOwnerForDirectCatalogQuery() {
        MediaGatewayClient client = mock(MediaGatewayClient.class);
        MediaGatewayController controller = controller(true, client);
        MediaCatalogPage page = new MediaCatalogPage(List.of(), 0, 1, 100, List.of(), List.of());
        when(client.catalog(55L, "image/", "", "nature", "mountain", 1, 100, false, "correlation"))
                .thenReturn(page);

        assertThat(controller.catalog("image/", "", "nature", "mountain", 1, 100, false, request(55L)))
                .isEqualTo(page);
        verify(client).catalog(55L, "image/", "", "nature", "mountain", 1, 100, false, "correlation");
    }

    @Test
    void shouldBindOwnerForDirectoryScanLifecycle() {
        MediaGatewayClient client=mock(MediaGatewayClient.class);MediaGatewayController controller=controller(true,client);UUID operationId=UUID.randomUUID();StartDirectoryScan body=new StartDirectoryScan("scan-1","/media/movies","movies","Movies",true,"analysis-v1");OperationView operation=new OperationView(operationId,55L,"DIRECTORY_SCAN","RUNNING",Instant.EPOCH,Instant.EPOCH);when(client.startDirectoryScan(55L,body,"correlation")).thenReturn(operation);when(client.operation(55L,operationId,"correlation")).thenReturn(operation);when(client.cancel(55L,operationId,"correlation")).thenReturn(operation);assertThat(controller.startDirectoryScan(body,request(55L))).isEqualTo(operation);assertThat(controller.operation(operationId,request(55L))).isEqualTo(operation);assertThat(controller.cancel(operationId,request(55L))).isEqualTo(operation);
    }

    @Test
    void shouldBindOwnerForAnalysisCreation() {
        MediaGatewayClient client=mock(MediaGatewayClient.class);MediaGatewayController controller=controller(true,client);UUID mediaId=UUID.randomUUID();UUID operationId=UUID.randomUUID();StartAnalysis body=new StartAnalysis("analysis-1","analysis-v2",8,1.5);OperationView operation=new OperationView(operationId,55L,"ANALYSIS","PENDING",Instant.EPOCH,Instant.EPOCH);when(client.startAnalysis(55L,mediaId,body,"correlation")).thenReturn(operation);assertThat(controller.startAnalysis(mediaId,body,request(55L))).isEqualTo(operation);verify(client).startAnalysis(55L,mediaId,body,"correlation");
    }

    @Test
    void shouldBindOwnerWhenDeletingMedia() {
        MediaGatewayClient client = mock(MediaGatewayClient.class);
        MediaGatewayController controller = controller(true, client);
        UUID mediaId = UUID.randomUUID();

        controller.delete(mediaId, request(55L));

        verify(client).delete(55L, mediaId, "correlation");
    }

    private MockHttpServletRequest request(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,
                new GatewayPrincipal(userId, "user", List.of("USER"), null));
        request.setAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation");
        return request;
    }

    private MediaGatewayController controller(boolean enabled, MediaGatewayClient client) {
        return new MediaGatewayController(properties(enabled), client, new MediaPlaybackTicketService());
    }

    private GatewayProperties properties(boolean enabled) {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, false, Set.of(),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token",
                "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000,
                enabled, "http://media", "media-token", false, "", "");
    }
}
