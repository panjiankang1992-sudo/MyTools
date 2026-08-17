package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.reader.mapper.SyncedBookSourceMapper;
import com.yuyutian.mytools.reader.model.SaveBookSourceRequest;
import com.yuyutian.mytools.reader.model.SyncedBookSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 书源同步安全测试。
 */
class BookSourceSyncServiceTest {
    /**
     * 验证合法脱敏快照可写入。
     */
    @Test
    void shouldInsertSanitizedSource() {
        SyncedBookSourceMapper mapper = mock(SyncedBookSourceMapper.class);
        SaveBookSourceRequest request = request("{\"bookSourceUrl\":\"https://books.example\","
                + "\"bookSourceName\":\"Example\",\"header\":\"{}\"}");
        SyncedBookSource stored = new SyncedBookSource();
        stored.setRevision(1L);
        when(mapper.findById(3L, request.getSyncKey())).thenReturn(null, stored);

        assertTrue(new BookSourceSyncService(mapper, new ObjectMapper()).save(3L, request).isAccepted());
        verify(mapper).insert(any(SyncedBookSource.class));
    }

    /**
     * 验证字符串化请求头中的凭据也会被拒绝。
     */
    @Test
    void shouldRejectEmbeddedAuthorization() {
        SyncedBookSourceMapper mapper = mock(SyncedBookSourceMapper.class);
        SaveBookSourceRequest request = request("{\"bookSourceUrl\":\"https://books.example\","
                + "\"bookSourceName\":\"Example\","
                + "\"header\":\"{\\\"Authorization\\\":\\\"Bearer secret\\\"}\"}");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new BookSourceSyncService(mapper, new ObjectMapper()).save(3L, request));

        assertEquals(ErrorCode.READER_004.getCode(), exception.getCode());
    }

    private SaveBookSourceRequest request(String snapshot) {
        String sourceUrl = "https://books.example";
        SaveBookSourceRequest request = new SaveBookSourceRequest();
        request.setSyncKey("sha256:" + sha256(sourceUrl));
        request.setSourceUrl(sourceUrl);
        request.setSnapshotJson(snapshot);
        request.setUpdatedAt(100L);
        request.setRevision(0L);
        return request;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
