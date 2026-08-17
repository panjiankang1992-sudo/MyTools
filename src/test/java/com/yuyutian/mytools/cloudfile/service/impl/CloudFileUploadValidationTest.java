package com.yuyutian.mytools.cloudfile.service.impl;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CloudFileUploadValidationTest {

    @Test
    void shouldRejectTraversalFilenameBeforeResolvingAccount() {
        CloudFileServiceImpl service = new CloudFileServiceImpl(mock(WebdavAccountMapper.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadFileStream(42L, 7L, "/books", "../secret.txt",
                        new ByteArrayInputStream(new byte[0]), 0));

        assertEquals("30005", exception.getCode());
    }

    @Test
    void shouldRejectTraversalDirectoryBeforeResolvingAccount() {
        CloudFileServiceImpl service = new CloudFileServiceImpl(mock(WebdavAccountMapper.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadFileStream(42L, 7L, "/books/../private", "book.txt",
                        new ByteArrayInputStream(new byte[0]), 0));

        assertEquals("30005", exception.getCode());
    }
}
