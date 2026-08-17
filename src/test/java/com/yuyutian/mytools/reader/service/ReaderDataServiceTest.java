package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.mapper.ReaderMarkerMapper;
import com.yuyutian.mytools.reader.mapper.ReadingProgressMapper;
import com.yuyutian.mytools.reader.mapper.ShelfBookMapper;
import com.yuyutian.mytools.reader.mapper.SyncedBookSourceMapper;
import com.yuyutian.mytools.reader.model.ReaderDataDeleteResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阅读数据生命周期服务测试。
 */
class ReaderDataServiceTest {
    /**
     * 验证删除覆盖所有同步表并汇总行数。
     */
    @Test
    void shouldDeleteEveryReaderDomainTable() {
        ShelfBookMapper shelf = mock(ShelfBookMapper.class);
        SyncedBookSourceMapper sources = mock(SyncedBookSourceMapper.class);
        ReadingProgressMapper progress = mock(ReadingProgressMapper.class);
        ReaderMarkerMapper markers = mock(ReaderMarkerMapper.class);
        when(shelf.deleteByUserId(8L)).thenReturn(2);
        when(sources.deleteByUserId(8L)).thenReturn(3);
        when(progress.deleteByUserId(8L)).thenReturn(4);
        when(markers.deleteByUserId(8L)).thenReturn(5);

        ReaderDataDeleteResponse response = new ReaderDataService(shelf, sources, progress, markers).deleteAll(8L);

        assertEquals(14, response.getDeletedRecords());
        verify(shelf).deleteByUserId(8L);
        verify(sources).deleteByUserId(8L);
        verify(progress).deleteByUserId(8L);
        verify(markers).deleteByUserId(8L);
    }
}
