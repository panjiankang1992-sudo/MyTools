package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookSourceRuntimeReaderServiceTest {
    @Test
    void returnsCachedContentWithoutCallingRuntime() {
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        BookSourceChapterCache cache = mock(BookSourceChapterCache.class);
        BookSourceRuntimeReaderModels.Content cached =
                new BookSourceRuntimeReaderModels.Content("text", "cached", List.of());
        when(cache.get(7L, "https://source.example", "https://book.example/1", 1))
                .thenReturn(Optional.of(cached));
        BookSourceRuntimeReaderService service = new BookSourceRuntimeReaderService(runtimeClient, cache);

        BookSourceRuntimeReaderModels.Content result = service.content(7L, " https://source.example ",
                " https://book.example/1 ", 1);

        assertThat(result).isEqualTo(cached);
        verify(runtimeClient, never()).content(7L, "https://source.example", "https://book.example/1", 1);
    }

    @Test
    void cachesContentLoadedFromRuntime() {
        ReaderRuntimeClient runtimeClient = mock(ReaderRuntimeClient.class);
        BookSourceChapterCache cache = mock(BookSourceChapterCache.class);
        BookSourceRuntimeReaderModels.Content loaded =
                new BookSourceRuntimeReaderModels.Content("text", "loaded", List.of());
        when(cache.get(7L, "https://source.example", "https://book.example/1", 1))
                .thenReturn(Optional.empty());
        when(runtimeClient.content(7L, "https://source.example", "https://book.example/1", 1))
                .thenReturn(loaded);
        BookSourceRuntimeReaderService service = new BookSourceRuntimeReaderService(runtimeClient, cache);

        assertThat(service.content(7L, "https://source.example", "https://book.example/1", 1))
                .isEqualTo(loaded);
        verify(cache).put(7L, "https://source.example", "https://book.example/1", 1, loaded);
    }
}
