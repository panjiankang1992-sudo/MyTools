package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CacheView;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CreatePrefetch;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.PrefetchView;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CatalogView;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.ImportView;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.ReaderGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.yuyutian.mytools.gateway.model.ReaderSearchGatewayModels.*;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateDiscovery;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateHealthCheck;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.DiscoveryView;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.HealthCheckView;

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

    @Test
    void shouldInjectOwnerIntoSearchLifecycle() {
        ReaderGatewayClient client=mock(ReaderGatewayClient.class);ReaderGatewayController controller=new ReaderGatewayController(properties(true),client);CreateSearch body=new CreateSearch("search-1","Book","FUZZY",1,List.of(new SourceSnapshot("source","Source","https://source.example",1,Map.of())));UUID id=UUID.randomUUID();SearchView view=new SearchView(id,"QUEUED","Book","FUZZY",1,0,0,0,List.of(),java.time.Instant.EPOCH,java.time.Instant.EPOCH);when(client.createSearch(55L,body,"correlation")).thenReturn(view);when(client.search(55L,id,"correlation")).thenReturn(view);when(client.cancelSearch(55L,id,"correlation")).thenReturn(view);assertThat(controller.createSearch(body,request(55L))).isEqualTo(view);assertThat(controller.search(id,request(55L))).isEqualTo(view);assertThat(controller.cancelSearch(id,request(55L))).isEqualTo(view);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldInjectOwnerIntoBatchSourceSync() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        var source = new ReaderGatewayController.SourceRequest(
                "sha256:key", "https://source.example", "{\"bookSourceUrl\":\"https://source.example\"}", false);
        when(client.saveSources(org.mockito.ArgumentMatchers.anyList(), eq("correlation")))
                .thenReturn(Map.of("accepted", 1));

        assertThat(controller.saveSources(new ReaderGatewayController.SourceBatchRequest(List.of(source)),
                request(55L))).containsEntry("accepted", 1);

        ArgumentCaptor<List<Map<String, Object>>> payloads = ArgumentCaptor.forClass(List.class);
        verify(client).saveSources(payloads.capture(), eq("correlation"));
        assertThat(payloads.getValue().getFirst()).containsEntry("ownerId", 55L);
    }

    @Test
    void shouldInjectOwnerIntoEbookImportLifecycle() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        CreateImport body = new CreateImport("import-1", sourceId, "https://source.example/book", "Book", null);
        ImportView view = new ImportView(id, "QUEUED", sourceId, 1, "Book", null,
                null, null, null, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        CatalogView catalog = new CatalogView(id, List.of());
        when(client.createImport(55L, body, "correlation")).thenReturn(view);
        when(client.importView(55L, id, "correlation")).thenReturn(view);
        when(client.cancelImport(55L, id, "correlation")).thenReturn(view);
        when(client.importCatalog(55L, id, "correlation")).thenReturn(catalog);

        assertThat(controller.createImport(body, request(55L))).isEqualTo(view);
        assertThat(controller.importView(id, request(55L))).isEqualTo(view);
        assertThat(controller.cancelImport(id, request(55L))).isEqualTo(view);
        assertThat(controller.importCatalog(id, request(55L))).isEqualTo(catalog);
    }

    @Test
    void shouldInjectOwnerIntoSourceTaskLifecycles() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID discoveryId = UUID.randomUUID();
        UUID healthId = UUID.randomUUID();
        CreateDiscovery discoveryRequest = new CreateDiscovery("discover-1", "https://sources.example");
        CreateHealthCheck healthRequest = new CreateHealthCheck("health-1", "test");
        DiscoveryView discovery = new DiscoveryView(discoveryId, "QUEUED", "https://sources.example",
                0, 0, 0, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        HealthCheckView health = new HealthCheckView(healthId, "QUEUED", "test",
                0, 0, 0, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(client.createDiscovery(55L, discoveryRequest, "correlation")).thenReturn(discovery);
        when(client.createHealthCheck(55L, healthRequest, "correlation")).thenReturn(health);

        assertThat(controller.createDiscovery(discoveryRequest, request(55L))).isEqualTo(discovery);
        assertThat(controller.createHealthCheck(healthRequest, request(55L))).isEqualTo(health);
    }

    @Test
    void shouldInjectOwnerIntoChapterLifecycle() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        CreatePrefetch body = new CreatePrefetch("prefetch-1", sourceId,
                "https://source.example/book", List.of(0, 1));
        PrefetchView prefetch = new PrefetchView(id, "QUEUED", 2, 0,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        CacheView cache = new CacheView(sourceId, "https://source.example/book", 0, "Chapter",
                "chapter-1", "content", "hash", 7, java.time.Instant.MAX);
        when(client.createPrefetch(55L, body, "correlation")).thenReturn(prefetch);
        when(client.chapterCache(55L, sourceId, "https://source.example/book", "chapter-1", "correlation"))
                .thenReturn(cache);

        assertThat(controller.createPrefetch(body, request(55L))).isEqualTo(prefetch);
        assertThat(controller.chapterCache(sourceId, "https://source.example/book", "chapter-1", request(55L)))
                .isEqualTo(cache);
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
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
