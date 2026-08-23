package com.yuyutian.mytools.localfile.service.tagging;

import com.yuyutian.mytools.task.client.TaskSchedulerGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

class MediaTagSidecarTaskPublisherTest {

    @Test
    void shouldCreateIdempotentSidecarTaskWhenEnabled() {
        TaskSchedulerGateway gateway = mock(TaskSchedulerGateway.class);
        MediaTagSidecarProperties properties = new MediaTagSidecarProperties();
        properties.setEnabled(true);
        MediaTagSidecarTaskPublisher publisher = new MediaTagSidecarTaskPublisher(gateway, properties);
        String hash = "a".repeat(64);

        publisher.publish(new MediaTagSidecarTaskRequested(
                42L, "sample.jpg", "/data/sample.jpg", "/data/thumb.jpg", "image/jpeg", hash,
                List.of("legacy")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(gateway).create(eq("media_generate_tags"),
                eq("media_generate_tags:" + hash + ":media-tags-v1"),
                eq("MEDIA_FILE"), eq("42"), eq(40), parameters.capture());
        assertThat(parameters.getValue()).containsEntry("contentSha256", hash);
        assertThat(parameters.getValue()).containsEntry("legacyTags", List.of("legacy"));
    }

    @Test
    void shouldNotCallSchedulerWhenDisabled() {
        TaskSchedulerGateway gateway = mock(TaskSchedulerGateway.class);
        MediaTagSidecarTaskPublisher publisher = new MediaTagSidecarTaskPublisher(
                gateway, new MediaTagSidecarProperties());

        publisher.publish(new MediaTagSidecarTaskRequested(
                42L, "sample.jpg", "/data/sample.jpg", null, "image/jpeg", "a".repeat(64), List.of()));

        verify(gateway, never()).create(anyString(), anyString(), anyString(), anyString(), anyInt(), anyMap());
    }
}
