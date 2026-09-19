package com.yuyutian.mytools.reader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.controller.adaptation.AdaptationStyleAdminController;
import com.yuyutian.mytools.reader.controller.adaptation.ReaderAdaptationResponseFilter;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStyleModels.*;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationStyleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 管理发布不能被 APP 或普通 Reader 身份代理。 */
class AdaptationStyleAdminControllerTest {
    @TempDir Path temporary;
    private static final String PATH = "/api/v1/reader-admin/adaptation-style-templates";
    @Test
    void onlyIndependentTokenAllowsStrictPublication() throws Exception {
        var styles = mock(AdaptationStyleRepository.class);
        var file = temporary.resolve("style.key"); String token = "test-only-" + "a".repeat(40);
        Files.writeString(file, token);
        var summary = new Summary("cinematic", 1, "Cinematic", "Visual style", "a".repeat(64));
        when(styles.catalog()).thenReturn(new Catalog(List.of(summary)));
        when(styles.publish(any(), eq("operator-skill"))).thenReturn(new Snapshot(summary, "Visual guidance."));
        var mvc = MockMvcBuilders.standaloneSetup(new AdaptationStyleAdminController(styles, new ObjectMapper(), file.toString()))
                .setControllerAdvice(new ReaderExceptionHandler()).addFilters(new ReaderAdaptationResponseFilter()).build();
        for (String supplied : List.of("", "Bearer app-jwt", "Bearer reader-service-token")) {
            mvc.perform(get(PATH).header("Authorization", supplied)).andExpect(status().isUnauthorized());
            mvc.perform(post(PATH).header("Authorization", supplied).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(styles);
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));
        String body = "{\"idempotencyKey\":\"publish-1\",\"code\":\"cinematic\",\"expectedLatestVersion\":0,\"name\":\"Cinematic\",\"description\":\"Visual style\",\"prompt\":\"Visual guidance.\"}";
        for (String invalid : List.of(body + "{}", body.replace("Version\":0", "Version\":\"0\""),
                body.replace("Version\":0", "Version\":0.5"), body.replace("{", "{\"ownerId\":1,"), body.replace("\"prompt\":", "\"prompt\":\"duplicate\",\"prompt\":"))) {
            mvc.perform(post(PATH).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.prompt").doesNotExist());
        verify(styles, times(1)).publish(any(), eq("operator-skill"));
        var disabled = MockMvcBuilders.standaloneSetup(new AdaptationStyleAdminController(styles, new ObjectMapper(), "")).build();
        disabled.perform(get(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
}
