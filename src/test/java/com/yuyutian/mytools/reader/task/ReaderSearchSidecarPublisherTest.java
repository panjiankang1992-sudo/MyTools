package com.yuyutian.mytools.reader.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.client.TaskSchedulerGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReaderSearchSidecarPublisherTest {

    @Test
    void shouldCreateBoundedReaderSearchTaskWhenEnabled() {
        TaskSchedulerGateway gateway = mock(TaskSchedulerGateway.class);
        ReaderSearchSidecarProperties properties = new ReaderSearchSidecarProperties();
        properties.setEnabled(true);
        ReaderSearchSidecarPublisher publisher = new ReaderSearchSidecarPublisher(
                gateway, properties, new ObjectMapper());
        var event = new ReaderSearchSidecarRequested(7L, "example", 1, "FUZZY", List.of(Map.of(
                "id", "source-1", "url", "https://source.example", "name", "Source",
                "revision", 1, "snapshot", Map.of("enabled", true))));

        publisher.publish(event);

        verify(gateway).create(eq("reader_source_search"),
                matches("reader_source_search:[a-f0-9]{64}:reader-search-v1"),
                eq("READER_SEARCH"), eq("7"), eq(40), anyMap());
    }
}
