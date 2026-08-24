package com.yuyutian.mytools.media.task;

import com.yuyutian.mytools.task.client.TaskSchedulerGateway;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

class MediaProcessingSidecarPublisherTest {

    @Test
    void shouldResolveMigratedIdentityAndCreateDurableVideoAnalysisWhenEnabled() {
        TaskSchedulerGateway gateway = mock(TaskSchedulerGateway.class);
        LegacyMediaAnalysisTargetClient targetClient = mock(LegacyMediaAnalysisTargetClient.class);
        MediaProcessingSidecarProperties properties = new MediaProcessingSidecarProperties();
        properties.setEnabled(true);
        properties.setExecutorNode("media-node-1");
        MediaProcessingSidecarPublisher publisher = new MediaProcessingSidecarPublisher(
                gateway, properties, targetClient);
        String hash = "c".repeat(64);
        UUID mediaItemId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        when(targetClient.resolve(42L)).thenReturn(new LegacyMediaAnalysisTargetClient.AnalysisTarget(
                mediaItemId, assetId, 7L, "video.mp4", "video/mp4", 100L, hash));

        publisher.publish(new MediaProcessingSidecarRequested(
                42L, "/data/video.mp4", hash, "video/mp4"));

        verify(gateway).create(eq("media_analyze_video"),
                eq("media_analyze_video:" + mediaItemId + ":media-video-analysis-v1"),
                eq("MEDIA_ITEM"), eq(mediaItemId.toString()), eq(30),
                org.mockito.ArgumentMatchers.argThat(parameters ->
                        parameters.get("assetRegistryId").equals(assetId.toString())
                                && parameters.get("ownerId").equals(7L)
                                && parameters.get("contentSha256").equals(hash)),
                eq(java.util.Map.of("executor.node", "media-node-1")));
    }

    @Test
    void shouldNotCreateTaskForUnmigratedOrNonVideoMedia() {
        TaskSchedulerGateway gateway = mock(TaskSchedulerGateway.class);
        LegacyMediaAnalysisTargetClient targetClient = mock(LegacyMediaAnalysisTargetClient.class);
        MediaProcessingSidecarProperties properties = new MediaProcessingSidecarProperties();
        properties.setEnabled(true);
        properties.setExecutorNode("media-node-1");
        MediaProcessingSidecarPublisher publisher = new MediaProcessingSidecarPublisher(
                gateway, properties, targetClient);
        String hash = "d".repeat(64);
        when(targetClient.resolve(42L)).thenThrow(new IllegalStateException("not migrated"));

        publisher.publish(new MediaProcessingSidecarRequested(
                42L, "/data/video.mp4", hash, "video/mp4"));
        publisher.publish(new MediaProcessingSidecarRequested(
                43L, "/data/image.jpg", hash, "image/jpeg"));

        verify(gateway, never()).create(eq("media_analyze_video"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), anyMap(), anyMap());
    }
}
