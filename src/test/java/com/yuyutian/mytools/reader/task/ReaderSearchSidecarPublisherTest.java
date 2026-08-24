package com.yuyutian.mytools.reader.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderSearchSidecarPublisherTest {

    @Test
    void shouldCreateBoundedReaderSearchTaskWhenEnabled() {
        ReaderSearchSidecarClient client = mock(ReaderSearchSidecarClient.class);
        ReaderSearchSidecarProperties properties = new ReaderSearchSidecarProperties();
        properties.setEnabled(true);
        ReaderSearchSidecarPublisher publisher = new ReaderSearchSidecarPublisher(
                client, properties, new ObjectMapper());
        var event = new ReaderSearchSidecarRequested(7L, "example", 1, "FUZZY", List.of("example"), List.of(Map.of(
                "id", "source-1", "url", "https://source.example", "name", "Source",
                "revision", 1, "snapshot", Map.of("enabled", true))));

        when(client.create(eq(event), org.mockito.ArgumentMatchers.matches(
                "legacy-shadow:[a-f0-9]{64}:reader-search-v3")))
                .thenReturn(new ReaderSearchSidecarClient.SearchAccepted(UUID.randomUUID(), "QUEUED"));

        publisher.publish(event);

        verify(client).create(eq(event), org.mockito.ArgumentMatchers.matches(
                "legacy-shadow:[a-f0-9]{64}:reader-search-v3"));
    }

    @Test
    void shouldSubmitProbeModeAfterTermsAreExpanded() {
        ReaderSearchSidecarClient client = mock(ReaderSearchSidecarClient.class);
        ReaderSearchSidecarProperties properties = new ReaderSearchSidecarProperties();
        properties.setEnabled(true);
        ReaderSearchSidecarPublisher publisher = new ReaderSearchSidecarPublisher(
                client, properties, new ObjectMapper());
        var event = new ReaderSearchSidecarRequested(
                7L, "example", 1, "PROBE", List.of("hero", "lost prince"), List.of());
        when(client.create(eq(event), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ReaderSearchSidecarClient.SearchAccepted(UUID.randomUUID(), "QUEUED"));

        publisher.publish(event);

        verify(client).create(eq(event), org.mockito.ArgumentMatchers.anyString());
    }
}
