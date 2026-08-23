package com.yuyutian.mytools.media.task;

import com.yuyutian.mytools.task.client.TaskSchedulerGateway;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MediaProcessingSidecarPublisherTest {

    @Test
    void shouldCreateProbeThumbnailAndVideoAnalysisTasksWhenEnabled() {
        TaskSchedulerGateway gateway = mock(TaskSchedulerGateway.class);
        MediaProcessingSidecarProperties properties = new MediaProcessingSidecarProperties();
        properties.setEnabled(true);
        MediaProcessingSidecarPublisher publisher = new MediaProcessingSidecarPublisher(gateway, properties);
        String hash = "c".repeat(64);

        publisher.publish(new MediaProcessingSidecarRequested(
                42L, "/data/video.mp4", "/data/.thumbnails/42.jpg", hash, "video/mp4"));

        verify(gateway).create(eq("media_probe"), eq("media_probe:" + hash + ":media-probe-v1"),
                eq("MEDIA_FILE"), eq("42"), eq(30), anyMap());
        verify(gateway).create(eq("media_generate_thumbnail"),
                eq("media_generate_thumbnail:" + hash + ":media-thumbnail-v1"),
                eq("MEDIA_FILE"), eq("42"), eq(30), anyMap());
        verify(gateway).create(eq("media_analyze_video"),
                eq("media_analyze_video:" + hash + ":media-video-analysis-v1"),
                eq("MEDIA_FILE"), eq("42"), eq(30), anyMap());
    }
}
