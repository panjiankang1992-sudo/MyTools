package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import com.yuyutian.mytools.reader.service.BookSourceRuntimeReaderService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookSourceRuntimeReaderControllerTest {

    /**
     * 验证目录接口把当前认证用户和图书定位参数完整传给服务层。
     */
    @Test
    void shouldLoadCatalogForAuthenticatedUser() {
        BookSourceRuntimeReaderService service = mock(BookSourceRuntimeReaderService.class);
        BookSourceRuntimeReaderModels.Catalog expected = new BookSourceRuntimeReaderModels.Catalog(
                "Book", "Author", "Intro", "", "Chapter 2",
                List.of(new BookSourceRuntimeReaderModels.Chapter("Chapter 1", "https://example.com/1", 0)));
        when(service.catalog(7L, "https://source.example", "https://book.example/1")).thenReturn(expected);
        BookSourceRuntimeReaderController controller = new BookSourceRuntimeReaderController(service);

        var response = controller.catalog(7L, new BookSourceRuntimeReaderModels.CatalogRequest(
                "https://source.example", "https://book.example/1"));

        assertEquals(expected, response.getData());
        verify(service).catalog(7L, "https://source.example", "https://book.example/1");
    }

    /**
     * 验证正文接口保留章节序号并返回后端净化后的内容。
     */
    @Test
    void shouldLoadChapterContentForAuthenticatedUser() {
        BookSourceRuntimeReaderService service = mock(BookSourceRuntimeReaderService.class);
        BookSourceRuntimeReaderModels.Content expected = new BookSourceRuntimeReaderModels.Content(
                "text", "Paragraph", List.of());
        when(service.content(7L, "https://source.example", "https://book.example/1", 0)).thenReturn(expected);
        BookSourceRuntimeReaderController controller = new BookSourceRuntimeReaderController(service);

        var response = controller.content(7L, new BookSourceRuntimeReaderModels.ContentRequest(
                "https://source.example", "https://book.example/1", 0));

        assertEquals(expected, response.getData());
        verify(service).content(7L, "https://source.example", "https://book.example/1", 0);
    }
}
