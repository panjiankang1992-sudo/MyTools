package com.yuyutian.mytools.cloudfile.service.impl;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import com.yuyutian.mytools.webdav.model.WebdavAccount;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFileServiceRangeValidationTest {

    @Test
    void shouldRejectMultipleRangesBeforeOpeningRemoteConnection() {
        WebdavAccountMapper mapper = mock(WebdavAccountMapper.class);
        WebdavAccount account = new WebdavAccount();
        account.setId(7L);
        account.setUserId(42L);
        account.setType("nextcloud");
        account.setIsActive(1);
        when(mapper.selectById(7L)).thenReturn(account);
        CloudFileServiceImpl service = new CloudFileServiceImpl(mapper);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.openMediaStream(42L, 7L, "/movie.mp4", "bytes=0-9,20-29"));

        assertEquals("90003", exception.getCode());
    }
}
