package com.yuyutian.mytools.reader.task;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderCacheMaintenanceSidecarJobTest {

    @Test
    void shouldCreateOneIdempotentMaintenancePerHour() {
        ReaderCacheMaintenanceSidecarClient client = mock(ReaderCacheMaintenanceSidecarClient.class);
        ReaderCacheMaintenanceSidecarProperties properties = new ReaderCacheMaintenanceSidecarProperties();
        properties.setEnabled(true);
        when(client.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ReaderCacheMaintenanceSidecarClient.MaintenanceAccepted(
                        UUID.randomUUID(), UUID.randomUUID(), "QUEUED"));
        ReaderCacheMaintenanceSidecarJob job = new ReaderCacheMaintenanceSidecarJob(client, properties);

        job.submit();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(client).create(cutoff.capture(), key.capture());
        assertThat(cutoff.getValue()).isEqualTo(cutoff.getValue().truncatedTo(ChronoUnit.HOURS));
        assertThat(key.getValue()).isEqualTo(
                "reader-cache-maintenance:" + cutoff.getValue().getEpochSecond());
    }

    @Test
    void shouldNotCreateMaintenanceWhenDisabled() {
        ReaderCacheMaintenanceSidecarClient client = mock(ReaderCacheMaintenanceSidecarClient.class);
        ReaderCacheMaintenanceSidecarJob job = new ReaderCacheMaintenanceSidecarJob(
                client, new ReaderCacheMaintenanceSidecarProperties());

        job.submit();

        verify(client, never()).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}
