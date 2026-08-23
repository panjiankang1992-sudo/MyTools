package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderRuntimeProperties;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookSourceChapterCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesEntriesPerUserAndChapter() {
        ReaderRuntimeProperties properties = new ReaderRuntimeProperties();
        properties.setChapterCacheDir(temporaryDirectory.resolve("chapters").toString());
        BookSourceChapterCache cache = new BookSourceChapterCache(new ObjectMapper(), properties);
        BookSourceRuntimeReaderModels.Content content =
                new BookSourceRuntimeReaderModels.Content("text", "chapter body", List.of());

        cache.put(11L, "https://source.example", "https://book.example/1", 1, content);

        assertThat(cache.get(11L, "https://source.example", "https://book.example/1", 1))
                .contains(content);
        assertThat(cache.get(12L, "https://source.example", "https://book.example/1", 1))
                .isEmpty();
        assertThat(cache.get(11L, "https://source.example", "https://book.example/2", 2))
                .isEmpty();
    }

    @Test
    void ignoresOversizedChapterContent() {
        ReaderRuntimeProperties properties = new ReaderRuntimeProperties();
        properties.setChapterCacheDir(temporaryDirectory.resolve("oversized").toString());
        BookSourceChapterCache cache = new BookSourceChapterCache(new ObjectMapper(), properties);
        String content = "x".repeat(2 * 1024 * 1024 + 1);

        cache.put(11L, "https://source.example", "https://book.example/1", 1,
                new BookSourceRuntimeReaderModels.Content("text", content, List.of()));

        assertThat(cache.get(11L, "https://source.example", "https://book.example/1", 1)).isEmpty();
    }
}
