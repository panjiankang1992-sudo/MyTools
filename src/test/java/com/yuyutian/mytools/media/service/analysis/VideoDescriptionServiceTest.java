package com.yuyutian.mytools.media.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerClient;
import com.yuyutian.mytools.media.model.VideoDescription;
import com.yuyutian.mytools.media.model.VideoMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoDescriptionServiceTest {

    @Test
    void shouldReuseExistingTagsInModelPrompt() throws Exception {
        TaggerClient client = mock(TaggerClient.class);
        String description = "这是一段根据十二张时间序列截图生成的视频内容介绍。".repeat(10);
        when(client.analyzeVideoDescription(contains("纪录片, 自然"), anyList()))
                .thenReturn(new ObjectMapper().readTree(
                        "{\"summary\":\"山林中的自然观察\",\"description\":\"" + description + "\"}"));
        VideoDescriptionService service = new VideoDescriptionService(client);

        VideoDescription result = service.generate("sample.mp4",
                new VideoMetadata(60_000L, "mp4", "h264", "aac", 1920, 1080, 25D, 1_000_000L),
                List.of("纪录片", "自然"), List.of(Path.of("01.jpg")));

        assertThat(result.summary()).isEqualTo("山林中的自然观察");
        assertThat(result.description()).hasSizeBetween(200, 500);
    }

    @Test
    void shouldRetryWhenFirstDescriptionIsTooShort() throws Exception {
        TaggerClient client = mock(TaggerClient.class);
        String validDescription = "画面按时间顺序展示测试场景中的色彩、运动和声音变化。".repeat(12);
        when(client.analyzeVideoDescription(contains("Filename: sample.mp4"), anyList()))
                .thenReturn(new ObjectMapper().readTree(
                        "{\"summary\":\"测试视频\",\"description\":\"过短\"}"))
                .thenReturn(new ObjectMapper().readTree(
                        "{\"summary\":\"测试视频\",\"description\":\"" + validDescription + "\"}"));
        VideoDescriptionService service = new VideoDescriptionService(client);

        VideoDescription result = service.generate("sample.mp4",
                new VideoMetadata(60_000L, "mp4", "h264", "aac", 1920, 1080, 25D, 1_000_000L),
                List.of(), List.of(Path.of("01.jpg")));

        assertThat(result.description()).hasSizeBetween(200, 500);
        verify(client, times(2)).analyzeVideoDescription(contains("Filename: sample.mp4"), anyList());
    }

    @Test
    void shouldExpandModelAnalysisWhenBothResponsesRemainShort() throws Exception {
        TaggerClient client = mock(TaggerClient.class);
        when(client.analyzeVideoDescription(contains("Filename: sample.mp4"), anyList()))
                .thenReturn(new ObjectMapper().readTree(
                        "{\"summary\":\"色彩运动测试\",\"description\":\"画面展示持续变化的彩色图形。\"}"));
        VideoDescriptionService service = new VideoDescriptionService(client);

        VideoDescription result = service.generate("sample.mp4",
                new VideoMetadata(60_000L, "mp4", "h264", "aac", 1920, 1080, 25D, 1_000_000L),
                List.of("测试"), List.of(Path.of("01.jpg")));

        assertThat(result.summary()).isEqualTo("色彩运动测试");
        assertThat(result.description()).hasSizeBetween(200, 500).contains("十二个时间点");
        verify(client, times(2)).analyzeVideoDescription(contains("Filename: sample.mp4"), anyList());
    }

    @Test
    void shouldFallbackToMetadataWhenModelIsUnavailable() throws Exception {
        TaggerClient client = mock(TaggerClient.class);
        when(client.analyzeVideoDescription(contains("Filename: sample.mp4"), anyList()))
                .thenThrow(new IllegalStateException("model unavailable"));
        VideoDescriptionService service = new VideoDescriptionService(client);

        VideoDescription result = service.generate("sample.mp4",
                new VideoMetadata(90_000L, "mp4", "h264", "aac", 1280, 720, 25D, 800_000L),
                List.of("测试标签"), List.of(Path.of("01.jpg")));

        assertThat(result.summary()).isEqualTo("sample");
        assertThat(result.description()).hasSizeBetween(200, 500)
                .contains("1280×720", "测试标签", "视觉模型暂时未能返回稳定结果");
    }
}
