package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import com.yuyutian.mytools.localfile.service.ResourceStorageGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbookImportServiceTest {
    @TempDir
    Path root;

    private EbookImportService service;

    @BeforeEach
    void setUp() {
        LocalDirectoryMapper directoryMapper = mock(LocalDirectoryMapper.class);
        LocalFileService localFileService = mock(LocalFileService.class);
        ResourceStorageGuard storageGuard = mock(ResourceStorageGuard.class);
        BookSourceRuntimeReaderService readerService = mock(BookSourceRuntimeReaderService.class);
        LocalDirectory directory = new LocalDirectory();
        directory.setId(9L);
        directory.setDirectoryType("EBOOK");
        directory.setDirectoryPath(root.toString());
        when(directoryMapper.selectById(9L)).thenReturn(directory);
        service = new EbookImportService(directoryMapper, localFileService, storageGuard, readerService);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /**
     * 验证连续上传同名图书时全部保留且不会覆盖第一本。
     */
    @Test
    void shouldKeepEverySelectedBookWhenNamesCollide() throws Exception {
        MockMultipartFile first = new MockMultipartFile("file", "novel.txt", "text/plain",
                "first".getBytes());
        MockMultipartFile second = new MockMultipartFile("file", "novel.txt", "text/plain",
                "second".getBytes());

        var firstResult = service.upload(9L, first, "novel.txt");
        var secondResult = service.upload(9L, second, "novel.txt");

        assertEquals("novel.txt", firstResult.fileName());
        assertEquals("novel (2).txt", secondResult.fileName());
        assertTrue(Files.exists(root.resolve(firstResult.fileName())));
        assertTrue(Files.exists(root.resolve(secondResult.fileName())));
    }

    /**
     * 验证后端拒绝把非电子书扩展名写入远程书库。
     */
    @Test
    void shouldRejectUnsupportedUploadExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "script.sh", "text/plain",
                "unsafe".getBytes());

        assertThrows(BusinessException.class, () -> service.upload(9L, file, "script.sh"));
    }
}
