package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.mapper.ReaderMarkerMapper;
import com.yuyutian.mytools.reader.model.ReaderMarker;
import com.yuyutian.mytools.reader.model.ReaderMarkerSyncResponse;
import com.yuyutian.mytools.reader.model.SaveReaderMarkerRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阅读标记同步服务测试。
 */
class ReaderMarkerServiceTest {
    /**
     * 验证删除墓碑也会作为首次记录持久化。
     */
    @Test
    void shouldInsertInitialTombstone() {
        ReaderMarkerMapper mapper = mock(ReaderMarkerMapper.class);
        ReaderMarker stored = marker(1L, true);
        when(mapper.findById(9L, "bookmark-1")).thenReturn(null, stored);

        ReaderMarkerSyncResponse response = new ReaderMarkerService(mapper).save(9L, request(0L, true));

        assertTrue(response.isAccepted());
        assertTrue(response.getMarker().isDeleted());
        verify(mapper).insert(any(ReaderMarker.class));
    }

    /**
     * 验证旧版本不能复活已删除标记。
     */
    @Test
    void shouldRejectStaleUpdateAgainstTombstone() {
        ReaderMarkerMapper mapper = mock(ReaderMarkerMapper.class);
        ReaderMarker stored = marker(3L, true);
        when(mapper.findById(9L, "bookmark-1")).thenReturn(stored);

        ReaderMarkerSyncResponse response = new ReaderMarkerService(mapper).save(9L, request(2L, false));

        assertFalse(response.isAccepted());
        assertSame(stored, response.getMarker());
    }

    private SaveReaderMarkerRequest request(Long revision, boolean deleted) {
        SaveReaderMarkerRequest request = new SaveReaderMarkerRequest();
        request.setMarkerId("bookmark-1");
        request.setKind("BOOKMARK");
        request.setBookId("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        request.setChapterTitle("Chapter 1");
        request.setLocator(10L);
        request.setNote("");
        request.setCreatedAt(100L);
        request.setUpdatedAt(200L);
        request.setDeleted(deleted);
        request.setRevision(revision);
        return request;
    }

    private ReaderMarker marker(Long revision, boolean deleted) {
        ReaderMarker marker = new ReaderMarker();
        marker.setRevision(revision);
        marker.setDeleted(deleted);
        return marker;
    }
}
