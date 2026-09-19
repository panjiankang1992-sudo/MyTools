package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.controller.adaptation.AdaptationComparisonController;
import com.yuyutian.mytools.reader.controller.adaptation.ReaderAdaptationResponseFilter;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationComparison;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationComparisonService;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdaptationComparisonControllerTest {
    @Test
    void shouldReturnOnlyVersionComparisonAndDisableCachingIncludingErrors() throws Exception {
        var service = mock(AdaptationComparisonService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdaptationComparisonController(service))
                .setControllerAdvice(new ReaderExceptionHandler()).addFilters(new ReaderAdaptationResponseFilter()).build();
        UUID id = UUID.randomUUID();
        String path = "/api/v1/reader-state/chapter-adaptations/" + id + "/comparison";
        when(service.compare(41, id)).thenReturn(new AdaptationComparison(id, UUID.randomUUID(), "a".repeat(64), "b".repeat(64),
                "SIDE_BY_SIDE", List.of(), "Original", "Adapted", "PARAGRAPH_BUDGET"));
        mvc.perform(get(path + "?ownerId=41")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SIDE_BY_SIDE")).andExpect(jsonPath("$.original").value("Original"))
                .andExpect(jsonPath("$.neighbors").doesNotExist()).andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        when(service.compare(42, id)).thenThrow(new ChapterAdaptationException(ErrorCode.ADAPTATION_NOT_FOUND));
        mvc.perform(get(path + "?ownerId=42")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_030")).andExpect(header().string("Cache-Control", "no-store, private"));
    }
}
