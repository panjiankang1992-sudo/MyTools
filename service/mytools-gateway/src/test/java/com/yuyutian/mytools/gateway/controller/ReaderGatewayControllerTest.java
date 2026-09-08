package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CacheView;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CreatePrefetch;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.PrefetchView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookGenerationView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookExportView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookPlaybackManifest;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookBookAnalysisView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookVoicePlanView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookVoiceCatalogView;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookGeneration;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookExport;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookVoiceRevision;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookSpeakerRevision;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookPronunciationRevision;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.AudiobookPronunciationDictionaryView;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CatalogView;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateManagedImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.ImportView;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.MediaGatewayClient;
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
import static org.mockito.Mockito.times;
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
                "sha256:key", "https://source.example", "{\"bookSourceUrl\":\"https://source.example\"}",
                false, 1720000000000L, 2L);
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
        ImportView view = new ImportView(id, null, "QUEUED", sourceId, 1, "Book", null,
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
    void shouldBridgeOnlyReadyOwnedSupportedEbookMediaToManagedImport() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        MediaGatewayClient mediaClient = mock(MediaGatewayClient.class);
        GatewayProperties enabled = new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, true,
                Set.of(55L), false, Set.of(), false, Set.of(), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token", "identity-token",
                "reader-token", "drive-token", "download-token", 1000, 3000,
                true, "http://media", "media-token", false, "", "");
        ReaderGatewayController controller = new ReaderGatewayController(enabled, client, null,
                new com.yuyutian.mytools.gateway.service.AudiobookPlaybackTicketService(), mediaClient);
        UUID mediaItemId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        CreateManagedImport body = new CreateManagedImport("managed-1", mediaItemId, true);
        var media = new com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaView(mediaItemId, 55L, assetId,
                "Book.txt", "text/plain", 100L, "a".repeat(64), "READY", 1L, List.of(), null, null);
        ImportView expected = new ImportView(UUID.randomUUID(), null, "QUEUED", UUID.randomUUID(), 1,
                "Book.txt", null, null, null, null, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(mediaClient.view(55L, mediaItemId, "correlation")).thenReturn(media);
        when(client.createManagedImport(55L, body, media, "correlation")).thenReturn(expected);

        assertThat(controller.createManagedImport(body, request(55L))).isEqualTo(expected);
        verify(mediaClient).view(55L, mediaItemId, "correlation");
        verify(client).createManagedImport(55L, body, media, "correlation");
    }

    @Test
    void shouldBridgeReadyOwnedEpubMediaToManagedImport() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        MediaGatewayClient mediaClient = mock(MediaGatewayClient.class);
        GatewayProperties enabled = new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, true,
                Set.of(55L), false, Set.of(), false, Set.of(), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token", "identity-token",
                "reader-token", "drive-token", "download-token", 1000, 3000,
                true, "http://media", "media-token", false, "", "");
        ReaderGatewayController controller = new ReaderGatewayController(enabled, client, null,
                new com.yuyutian.mytools.gateway.service.AudiobookPlaybackTicketService(), mediaClient);
        UUID mediaItemId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        CreateManagedImport body = new CreateManagedImport("managed-epub", mediaItemId, true);
        var media = new com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaView(mediaItemId, 55L, assetId,
                "Book.epub", "application/epub+zip", 100L, "a".repeat(64), "READY", 1L, List.of(), null, null);
        ImportView expected = new ImportView(UUID.randomUUID(), null, "QUEUED", UUID.randomUUID(), 1,
                "Book.epub", null, null, null, null, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(mediaClient.view(55L, mediaItemId, "correlation")).thenReturn(media);
        when(client.createManagedImport(55L, body, media, "correlation")).thenReturn(expected);

        assertThat(controller.createManagedImport(body, request(55L))).isEqualTo(expected);
        verify(client).createManagedImport(55L, body, media, "correlation");
    }

    @Test
    void shouldInjectOwnerIntoAudiobookGenerationLifecycle() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID generationId = UUID.randomUUID();
        UUID ebookAssetId = UUID.randomUUID();
        CreateAudiobookGeneration body = new CreateAudiobookGeneration(ebookAssetId, "audiobook-1", "FULL", true);
        AudiobookGenerationView view = new AudiobookGenerationView(generationId, "QUEUED", "TEXT_EXTRACTING",
                "FULL", 1, 2, 0, 0, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(client.createAudiobookGeneration(55L, body, "correlation")).thenReturn(view);
        when(client.audiobookGeneration(55L, generationId, "correlation")).thenReturn(view);

        assertThat(controller.createAudiobookGeneration(body, request(55L))).isEqualTo(view);
        assertThat(controller.audiobookGeneration(generationId, request(55L))).isEqualTo(view);
    }

    @Test
    void shouldInjectOwnerIntoAudiobookVoiceRevision() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID sourceGenerationId = UUID.randomUUID();
        CreateAudiobookVoiceRevision body = new CreateAudiobookVoiceRevision("audiobook-revision-1",
                "CHARACTER:Lin", "VOLCENGINE", "voice-alt");
        AudiobookGenerationView view = new AudiobookGenerationView(UUID.randomUUID(), "QUEUED", "SYNTHESIZING",
                "REPAIR", 2, 2, 0, 0, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(client.createAudiobookVoiceRevision(55L, sourceGenerationId, body, "correlation")).thenReturn(view);

        assertThat(controller.createAudiobookVoiceRevision(sourceGenerationId, body, request(55L))).isEqualTo(view);
        verify(client).createAudiobookVoiceRevision(55L, sourceGenerationId, body, "correlation");
    }

    @Test
    void shouldInjectOwnerIntoAudiobookSpeakerRevision() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID sourceGenerationId = UUID.randomUUID();
        CreateAudiobookSpeakerRevision body = new CreateAudiobookSpeakerRevision("audiobook-speaker-revision-1",
                2, 4, "CHARACTER", "Chen");
        AudiobookGenerationView view = new AudiobookGenerationView(UUID.randomUUID(), "QUEUED", "SYNTHESIZING",
                "REPAIR", 2, 5, 0, 0, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(client.createAudiobookSpeakerRevision(55L, sourceGenerationId, body, "correlation")).thenReturn(view);

        assertThat(controller.createAudiobookSpeakerRevision(sourceGenerationId, body, request(55L))).isEqualTo(view);
        verify(client).createAudiobookSpeakerRevision(55L, sourceGenerationId, body, "correlation");
    }

    @Test
    void shouldInjectOwnerIntoAudiobookPronunciationRevisionAndDictionary() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID sourceGenerationId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        CreateAudiobookPronunciationRevision body = new CreateAudiobookPronunciationRevision(
                "audiobook-pronunciation-revision-1", "银行", "yin2 hang2");
        AudiobookGenerationView view = new AudiobookGenerationView(revisionId, "QUEUED",
                "PREPARING_PRONUNCIATION", "REPAIR", 2, 5, 0, 0, null, java.time.Instant.EPOCH,
                java.time.Instant.EPOCH);
        AudiobookPronunciationDictionaryView dictionary = new AudiobookPronunciationDictionaryView(revisionId,
                "a".repeat(64), List.of(new AudiobookPronunciationDictionaryView.Entry("银行", "yin2 hang2")));
        when(client.createAudiobookPronunciationRevision(55L, sourceGenerationId, body, "correlation"))
                .thenReturn(view);
        when(client.audiobookPronunciationDictionary(55L, revisionId, "correlation")).thenReturn(dictionary);

        assertThat(controller.createAudiobookPronunciationRevision(sourceGenerationId, body, request(55L)))
                .isEqualTo(view);
        assertThat(controller.audiobookPronunciationDictionary(revisionId, request(55L))).isEqualTo(dictionary);
        verify(client).createAudiobookPronunciationRevision(55L, sourceGenerationId, body, "correlation");
        verify(client).audiobookPronunciationDictionary(55L, revisionId, "correlation");
    }

    @Test
    void shouldCreateQueryAndTicketCompletedAudiobookZipExportForPrincipal() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID generationId = UUID.randomUUID();
        UUID exportId = UUID.randomUUID();
        CreateAudiobookExport body = new CreateAudiobookExport("audiobook-export-1", "ZIP");
        AudiobookExportView view = new AudiobookExportView(exportId, generationId, "ZIP", "COMPLETED",
                "COMPLETED", 2, 200L, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(client.createAudiobookExport(55L, generationId, body, "correlation")).thenReturn(view);
        when(client.audiobookExport(55L, generationId, exportId, "correlation")).thenReturn(view);

        assertThat(controller.createAudiobookExport(generationId, body, request(55L))).isEqualTo(view);
        assertThat(controller.audiobookExport(generationId, exportId, request(55L))).isEqualTo(view);
        Map<String, Object> ticket = controller.audiobookExportDownloadTicket(generationId, exportId, request(55L));

        assertThat(ticket.get("downloadPath")).isInstanceOf(String.class);
        assertThat((String) ticket.get("downloadPath")).matches("/api/app/v1/audiobook-export/tickets/[a-f0-9]{32}");
        verify(client).createAudiobookExport(55L, generationId, body, "correlation");
        verify(client, times(2)).audiobookExport(55L, generationId, exportId, "correlation");
    }

    @Test
    void shouldIssueAudiobookTicketOnlyForReadyChapterOwnedByPrincipal() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID generationId = UUID.randomUUID();
        AudiobookPlaybackManifest manifest = new AudiobookPlaybackManifest(generationId, 1, "COMPLETED", List.of(
                new AudiobookPlaybackManifest.Chapter(0, "Chapter", "READY", UUID.randomUUID(), 1234L)));
        when(client.audiobookPlaybackManifest(55L, generationId, "correlation")).thenReturn(manifest);

        Map<String, Object> result = controller.audiobookPlayTicket(generationId, 0, request(55L));

        assertThat(result.get("ticket")).isInstanceOf(String.class);
        assertThat((String) result.get("streamPath")).matches("/api/app/v1/audiobook-playback/tickets/[a-f0-9]{32}");
        verify(client).audiobookPlaybackManifest(55L, generationId, "correlation");
        assertThatThrownBy(() -> controller.audiobookPlayTicket(generationId, 1, request(55L)))
                .isInstanceOf(com.yuyutian.mytools.gateway.service.GatewayNotFoundException.class);
    }

    @Test
    void shouldIssuePreviewTicketOnlyForCompletedGenerationWithApprovedPreview() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID generationId = UUID.randomUUID();
        AudiobookGenerationView generation = new AudiobookGenerationView(generationId, "COMPLETED", "COMPLETED",
                "FULL", 1, 1, 1, 0, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        var catalog = new AudiobookVoiceCatalogView(generationId, List.of(
                new AudiobookVoiceCatalogView.Voice("VOLCENGINE", "narrator", "zh-CN", "NEUTRAL", "ADULT",
                        List.of("calm"), true, "v1", true, true)));
        when(client.audiobookGeneration(55L, generationId, "correlation")).thenReturn(generation);
        when(client.audiobookVoiceCatalog(55L, generationId, "correlation")).thenReturn(catalog);

        Map<String, Object> result = controller.audiobookVoicePreviewTicket(generationId, "VOLCENGINE", "narrator",
                request(55L));

        assertThat((String) result.get("streamPath")).matches(
                "/api/app/v1/audiobook-voice-preview/tickets/[a-f0-9]{32}");
        verify(client).audiobookGeneration(55L, generationId, "correlation");
        verify(client).audiobookVoiceCatalog(55L, generationId, "correlation");
    }

    @Test
    void shouldExposeOnlyPrincipalOwnedAudiobookBookAnalysis() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID generationId = UUID.randomUUID();
        AudiobookBookAnalysisView analysis = new AudiobookBookAnalysisView(generationId, List.of(), List.of(), List.of());
        when(client.audiobookBookAnalysis(55L, generationId, "correlation")).thenReturn(analysis);

        assertThat(controller.audiobookBookAnalysis(generationId, request(55L))).isEqualTo(analysis);
        verify(client).audiobookBookAnalysis(55L, generationId, "correlation");
    }

    @Test
    void shouldExposeOnlyPrincipalOwnedAudiobookVoicePlan() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID generationId = UUID.randomUUID();
        AudiobookVoicePlanView plan = new AudiobookVoicePlanView(generationId, List.of());
        when(client.audiobookVoicePlan(55L, generationId, "correlation")).thenReturn(plan);

        assertThat(controller.audiobookVoicePlan(generationId, request(55L))).isEqualTo(plan);
        verify(client).audiobookVoicePlan(55L, generationId, "correlation");
    }

    @Test
    void shouldExposeOnlyPrincipalOwnedAudiobookVoiceCatalog() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        ReaderGatewayController controller = new ReaderGatewayController(properties(true), client);
        UUID generationId = UUID.randomUUID();
        AudiobookVoiceCatalogView catalog = new AudiobookVoiceCatalogView(generationId, List.of());
        when(client.audiobookVoiceCatalog(55L, generationId, "correlation")).thenReturn(catalog);

        assertThat(controller.audiobookVoiceCatalog(generationId, request(55L))).isEqualTo(catalog);
        verify(client).audiobookVoiceCatalog(55L, generationId, "correlation");
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
