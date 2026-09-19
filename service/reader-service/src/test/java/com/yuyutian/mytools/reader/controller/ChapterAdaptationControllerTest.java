package com.yuyutian.mytools.reader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.controller.adaptation.ChapterAdaptationController;
import com.yuyutian.mytools.reader.controller.adaptation.ReaderAdaptationResponseFilter;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationInput;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationViews;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 私网契约只验证解析与投影；真实 Gateway 认证链仍由后续集成测试覆盖。 */
class ChapterAdaptationControllerTest {
    private static final String BODY = """
            {"idempotencyKey":"fixture-request","intent":"Preserve the original outcome.",
             "expectedBindingRevision":1,"expectedCatalogRevision":2}
            """;
    private final UUID shelf = UUID.randomUUID();
    private final UUID chapter = UUID.randomUUID();
    private final UUID adaptation = UUID.randomUUID();
    private final String createPath = "/api/v1/reader-state/shelves/" + shelf + "/chapters/" + chapter + "/adaptations?ownerId=41";
    private final String versionPath = "/api/v1/reader-state/chapter-adaptations/" + adaptation;
    private ChapterAdaptationService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(ChapterAdaptationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChapterAdaptationController(service, new ObjectMapper().findAndRegisterModules()))
                .setControllerAdvice(new ReaderExceptionHandler()).addFilters(new ReaderAdaptationResponseFilter()).build();
    }

    @Test
    void shouldAcceptOnlyCanonicalIdentityAndReturnAsyncReceipt() throws Exception {
        var accepted = new AdaptationViews.Accepted(adaptation, chapter, 1, AdaptationRequestKind.INITIAL,
                AdaptationStatus.PENDING_DISPATCH, "CONTEXT_PENDING", 2000, Instant.parse("2026-09-10T00:00:00Z"));
        when(service.create(eq(41L), eq(shelf), eq(chapter), any())).thenReturn(accepted);
        mvc.perform(post(createPath).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.adaptationId").value(adaptation.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_DISPATCH"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        verify(service).create(41, shelf, chapter, new AdaptationInput("fixture-request", "Preserve the original outcome.", 1, 2, null));
    }

    @Test
    void shouldRejectAdditionalTextOwnerModelAndAddress() throws Exception {
        for (String field : List.of("ownerId", "text", "model", "chapterUrl", "systemPrompt", "triggerAdaptationId")) {
            String body = BODY.stripTrailing();
            body = body.substring(0, body.length() - 1) + ",\"" + field + "\":\"untrusted\"}";
            mvc.perform(post(createPath).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("READER_060"))
                    .andExpect(header().string("Cache-Control", "no-store, private"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectDuplicateKeysTrailingDocumentsAndInvalidShapes() throws Exception {
        for (String body : List.of(BODY.replace("\"intent\":", "\"intent\":\"Duplicate\",\"intent\":"),
                BODY + " {}", "[]", "null", BODY.replace("\"expectedBindingRevision\":1", "\"expectedBindingRevision\":1.2"),
                BODY.replace("\"expectedBindingRevision\":1", "\"expectedBindingRevision\":9223372036854775808"),
                BODY.replace("\"expectedBindingRevision\":1", "\"expectedBindingRevision\":\"1\""),
                BODY.replace("\"intent\":\"Preserve the original outcome.\",", ""), " ".repeat(32769))) {
            mvc.perform(post(createPath).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("READER_060"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldMapOptimizeAndRegenerateToDifferentKinds() throws Exception {
        mvc.perform(post(versionPath + "/optimize?ownerId=41").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());
        mvc.perform(post(versionPath + "/regenerate?ownerId=41").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());
        var input = new AdaptationInput("fixture-request", "Preserve the original outcome.", 1, 2, null);
        verify(service).derive(41, adaptation, AdaptationRequestKind.OPTIMIZE, input);
        verify(service).derive(41, adaptation, AdaptationRequestKind.REGENERATE, input);
    }

    @Test
    void shouldPollOnlyLightweightStatus() throws Exception {
        when(service.progress(41, adaptation)).thenReturn(new AdaptationViews.Progress(adaptation, AdaptationStatus.GENERATING,
                "GENERATING", 4, 0, null, 2000, Instant.parse("2026-09-10T00:00:00Z"), null, null));
        mvc.perform(get(versionPath + "/status?ownerId=41")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4)).andExpect(jsonPath("$.intent").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist()).andExpect(jsonPath("$.modelId").doesNotExist());
    }

    @Test
    void shouldKeepStableGoneAndConsentErrorsUncached() throws Exception {
        when(service.create(eq(41L), eq(shelf), eq(chapter), any()))
                .thenThrow(new ChapterAdaptationException(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED));
        mvc.perform(post(createPath).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("READER_052"));
        when(service.detail(41, adaptation)).thenThrow(new ChapterAdaptationException(ErrorCode.ADAPTATION_HISTORY_DELETED));
        mvc.perform(get(versionPath + "?ownerId=41")).andExpect(status().isGone())
                .andExpect(header().string("Cache-Control", "no-store, private")).andExpect(jsonPath("$.code").value("READER_056"));
    }

    @Test
    void shouldRequireExplicitEmptyCancelRequestAndKeepOldRoutesUnchanged() throws Exception {
        mvc.perform(post(versionPath + "/cancel?ownerId=41").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"unexpected\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(post(versionPath + "/cancel?ownerId=41")).andExpect(status().isOk());
        verify(service).cancel(41, adaptation);
        mvc.perform(get("/api/v1/reader-state/unrelated?ownerId=41")).andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Cache-Control"));
    }
}
