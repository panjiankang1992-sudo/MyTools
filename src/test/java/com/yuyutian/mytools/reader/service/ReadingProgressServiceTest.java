package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.mapper.ReadingProgressMapper;
import com.yuyutian.mytools.reader.model.ReadingProgress;
import com.yuyutian.mytools.reader.model.ReadingProgressSyncResponse;
import com.yuyutian.mytools.reader.model.SaveReadingProgressRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阅读进度同步服务测试。
 */
class ReadingProgressServiceTest {

    /**
     * 验证首次同步会创建服务端版本。
     */
    @Test
    void shouldInsertInitialProgress() {
        ReadingProgressMapper mapper = mock(ReadingProgressMapper.class);
        ReadingProgress stored = progress(1L);
        when(mapper.findByUserIdAndBookId(7L, bookKey())).thenReturn(null, stored);
        ReadingProgressService service = new ReadingProgressService(mapper);

        ReadingProgressSyncResponse response = service.save(7L, request(0L));

        assertTrue(response.isAccepted());
        assertSame(stored, response.getProgress());
        verify(mapper).insert(any(ReadingProgress.class));
    }

    /**
     * 验证旧版本不会覆盖服务端权威进度。
     */
    @Test
    void shouldReturnConflictForStaleRevision() {
        ReadingProgressMapper mapper = mock(ReadingProgressMapper.class);
        ReadingProgress stored = progress(4L);
        when(mapper.findByUserIdAndBookId(7L, bookKey())).thenReturn(stored);
        ReadingProgressService service = new ReadingProgressService(mapper);

        ReadingProgressSyncResponse response = service.save(7L, request(3L));

        assertFalse(response.isAccepted());
        assertSame(stored, response.getProgress());
    }

    /**
     * 验证删除进度以墓碑形式参与版本更新。
     */
    @Test
    void shouldPersistDeletionTombstone() {
        ReadingProgressMapper mapper = mock(ReadingProgressMapper.class);
        ReadingProgress existing = progress(2L);
        ReadingProgress deleted = progress(3L);
        deleted.setDeleted(true);
        when(mapper.findByUserIdAndBookId(7L, bookKey())).thenReturn(existing, deleted);
        when(mapper.updateIfRevisionMatches(any(ReadingProgress.class), any(Long.class))).thenReturn(1);
        SaveReadingProgressRequest request = request(2L);
        request.setDeleted(true);

        ReadingProgressSyncResponse response = new ReadingProgressService(mapper).save(7L, request);

        assertTrue(response.isAccepted());
        assertTrue(response.getProgress().isDeleted());
    }

    private SaveReadingProgressRequest request(Long revision) {
        SaveReadingProgressRequest request = new SaveReadingProgressRequest();
        request.setBookId(bookKey());
        request.setChapterTitle("Chapter 1");
        request.setLocator(12L);
        request.setPercentage(25);
        request.setUpdatedAt(1000L);
        request.setRevision(revision);
        return request;
    }

    private ReadingProgress progress(Long revision) {
        ReadingProgress progress = new ReadingProgress();
        progress.setRevision(revision);
        return progress;
    }

    private String bookKey() {
        return "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }
}
