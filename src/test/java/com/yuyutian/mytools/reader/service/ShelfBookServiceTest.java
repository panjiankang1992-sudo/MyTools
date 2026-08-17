package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.reader.mapper.ShelfBookMapper;
import com.yuyutian.mytools.reader.model.SaveShelfBookRequest;
import com.yuyutian.mytools.reader.model.ShelfBook;
import com.yuyutian.mytools.reader.model.ShelfBookSyncResponse;
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
 * 书架同步服务测试。
 */
class ShelfBookServiceTest {
    /**
     * 验证设备本地图书不会进入服务端书架。
     */
    @Test
    void shouldRejectLocalBook() {
        ShelfBookMapper mapper = mock(ShelfBookMapper.class);
        SaveShelfBookRequest request = request("local:file://private/book.epub");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new ShelfBookService(mapper).save(1L, request));

        assertEquals(ErrorCode.READER_002.getCode(), exception.getCode());
    }

    /**
     * 验证合法远程图书可首次写入。
     */
    @Test
    void shouldInsertRemoteBook() {
        ShelfBookMapper mapper = mock(ShelfBookMapper.class);
        SaveShelfBookRequest request = request("remote:12:/books/a.epub");
        ShelfBook stored = new ShelfBook();
        stored.setRevision(1L);
        when(mapper.findById(2L, request.getSyncKey())).thenReturn(null, stored);

        ShelfBookSyncResponse response = new ShelfBookService(mapper).save(2L, request);

        assertTrue(response.isAccepted());
        verify(mapper).insert(any(ShelfBook.class));
    }

    private SaveShelfBookRequest request(String bookId) {
        SaveShelfBookRequest request = new SaveShelfBookRequest();
        request.setSyncKey("sha256:" + sha256(bookId));
        request.setBookId(bookId);
        request.setName("Book");
        request.setAuthor("Author");
        request.setOrigin("remote");
        request.setFormat("epub");
        request.setResourceUri("/books/a.epub");
        request.setSourceId("12");
        request.setRemoteCoverUrl("");
        request.setUpdatedAt(100L);
        request.setRevision(0L);
        return request;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
