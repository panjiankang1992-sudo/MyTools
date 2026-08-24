package com.yuyutian.mytools.reader.task;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderDiscoverySidecarPublisherTest {

    @Test
    void shouldCreateDiscoveryWhenEnabled() {
        ReaderDiscoverySidecarClient client = mock(ReaderDiscoverySidecarClient.class);
        ReaderDiscoverySidecarProperties properties = new ReaderDiscoverySidecarProperties();
        properties.setEnabled(true);
        ReaderDiscoverySidecarPublisher publisher = new ReaderDiscoverySidecarPublisher(client, properties);
        var event = new ReaderDiscoverySidecarRequested(
                "legacy-1", 7L, "https://repository.example/sources.json");
        when(client.create(event)).thenReturn(new ReaderDiscoverySidecarClient.DiscoveryAccepted(
                UUID.randomUUID(), UUID.randomUUID(), "QUEUED"));

        publisher.publish(event);

        verify(client).create(event);
    }

    @Test
    void shouldKeepOldDiscoveryIndependentWhenDisabled() {
        ReaderDiscoverySidecarClient client = mock(ReaderDiscoverySidecarClient.class);
        ReaderDiscoverySidecarPublisher publisher = new ReaderDiscoverySidecarPublisher(
                client, new ReaderDiscoverySidecarProperties());
        var event = new ReaderDiscoverySidecarRequested(
                "legacy-1", 7L, "https://repository.example/sources.json");

        publisher.publish(event);

        verify(client, never()).create(event);
    }
}
