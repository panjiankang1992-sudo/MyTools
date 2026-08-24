package com.yuyutian.mytools.reader.task;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderImportSidecarPublisherTest {

    @Test
    void shouldCreateImportOnlyWhenEnabled() {
        ReaderImportSidecarClient client = mock(ReaderImportSidecarClient.class);
        ReaderImportSidecarProperties properties = new ReaderImportSidecarProperties();
        properties.setEnabled(true);
        ReaderImportSidecarPublisher publisher = new ReaderImportSidecarPublisher(client, properties);
        var event = new ReaderImportSidecarRequested("legacy-1", 7L, "https://source.example",
                "https://source.example/book", "Book", "Author");
        when(client.create(event)).thenReturn(new ReaderImportSidecarClient.ImportAccepted(
                UUID.randomUUID(), UUID.randomUUID(), "QUEUED"));

        publisher.publish(event);

        verify(client).create(event);
    }

    @Test
    void shouldKeepOldImportIndependentWhenDisabled() {
        ReaderImportSidecarClient client = mock(ReaderImportSidecarClient.class);
        ReaderImportSidecarPublisher publisher = new ReaderImportSidecarPublisher(
                client, new ReaderImportSidecarProperties());
        var event = new ReaderImportSidecarRequested("legacy-1", 7L, "https://source.example",
                "https://source.example/book", "Book", null);

        publisher.publish(event);

        verify(client, never()).create(event);
    }
}
