package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.controller.adaptation.ShelfChapterController;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.SourceShelfChapterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShelfChapterControllerTest {
    private final UUID shelfId = UUID.randomUUID();
    private final UUID chapterId = UUID.randomUUID();
    private final String root = "/api/v1/reader-state/shelves/" + shelfId;
    private SourceShelfChapterService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(SourceShelfChapterService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ShelfChapterController(service))
                .setControllerAdvice(new ReaderExceptionHandler()).build();
    }

    @Test
    void shouldAcceptOnlyKeyAndReturnDurablePreparationStatus() throws Exception {
        when(service.ensure(41, shelfId, "request")).thenReturn(new ShelfChapterModels.Capability(
                shelfId, "PREPARING", 1, 0, null, null, 1500));
        mvc.perform(post(root + "/chapter-adaptation-capability/ensure?ownerId=41")
                .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"request\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.pollAfterMs").value(1500));
        verify(service).ensure(41, shelfId, "request");
    }

    @Test
    void shouldRejectExtraOwnerUrlAndTextFieldsBeforeServiceCall() throws Exception {
        for (String extra : List.of("ownerId", "bookUrl", "chapterUrl", "text", "model")) {
            mvc.perform(post(root + "/chapter-adaptation-capability/ensure?ownerId=41")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"idempotencyKey\":\"request\",\"" + extra + "\":\"untrusted\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("READER_060"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturnCanonicalIdentityWithoutLocatorOrOwnerData() throws Exception {
        when(service.catalog(41, shelfId, 200, null)).thenReturn(new ShelfChapterModels.Catalog(shelfId, 1, 1, "a".repeat(64),
                List.of(new ShelfChapterModels.Chapter(chapterId, 0, "First", "TEXT", null, true, null)), null));
        mvc.perform(get(root + "/chapters?ownerId=41")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].chapterId").value(chapterId.toString()))
                .andExpect(jsonPath("$.items[0].resourceUri").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    void shouldNormalizeMissingObjectAndStaleCatalogErrors() throws Exception {
        when(service.content(42, shelfId, chapterId)).thenThrow(new ChapterAdaptationException(ErrorCode.READER_STATE_NOT_FOUND));
        mvc.perform(get(root + "/chapters/" + chapterId + "/content?ownerId=42"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("READER_017"));
        when(service.catalog(41, shelfId, 200, "old")).thenThrow(new ChapterAdaptationException(ErrorCode.CHAPTER_CATALOG_STALE));
        mvc.perform(get(root + "/chapters?ownerId=41&cursor=old"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READER_034"));
    }
}
