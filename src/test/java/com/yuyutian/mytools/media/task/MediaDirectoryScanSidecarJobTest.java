package com.yuyutian.mytools.media.task;

import com.yuyutian.mytools.localfile.service.ResourceStorageGuard;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaDirectoryScanSidecarJobTest {

    @Test
    void shouldSubmitDailyScanWhenEnabledAndStorageIsAvailable() {
        MediaDirectoryScanSidecarClient client = mock(MediaDirectoryScanSidecarClient.class);
        MediaDirectoryScanSidecarProperties properties = new MediaDirectoryScanSidecarProperties();
        properties.setEnabled(true);
        ResourceStorageGuard storageGuard = mock(ResourceStorageGuard.class);
        when(storageGuard.isAvailable()).thenReturn(true);
        String root = Path.of("target/media-sidecar-root").toAbsolutePath().normalize().toString();
        when(client.create(eq(root), matches("legacy-directory-scan:\\d{4}-\\d{2}-\\d{2}:[a-f0-9]{64}")))
                .thenReturn(new MediaDirectoryScanSidecarClient.ScanAccepted(
                        UUID.randomUUID(), UUID.randomUUID(), "QUEUED"));

        new MediaDirectoryScanSidecarJob(client, properties, storageGuard, root).submit();

        verify(client).create(eq(root), matches("legacy-directory-scan:\\d{4}-\\d{2}-\\d{2}:[a-f0-9]{64}"));
    }

    @Test
    void shouldNotSubmitWhenStorageIsUnavailable() {
        MediaDirectoryScanSidecarClient client = mock(MediaDirectoryScanSidecarClient.class);
        MediaDirectoryScanSidecarProperties properties = new MediaDirectoryScanSidecarProperties();
        properties.setEnabled(true);
        ResourceStorageGuard storageGuard = mock(ResourceStorageGuard.class);
        when(storageGuard.isAvailable()).thenReturn(false);

        new MediaDirectoryScanSidecarJob(client, properties, storageGuard, "/media").submit();

        verify(client, never()).create(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
