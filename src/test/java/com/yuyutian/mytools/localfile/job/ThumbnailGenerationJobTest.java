package com.yuyutian.mytools.localfile.job;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import com.yuyutian.mytools.localfile.service.ResourceStorageGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体缩略图后台任务退避测试。
 */
class ThumbnailGenerationJobTest {

    private ThumbnailGenerationJob job;

    /**
     * 释放后台任务线程池。
     */
    @AfterEach
    void tearDown() {
        if (job != null) job.shutdownExecutor();
    }

    /**
     * 验证损坏媒体首次失败后不会在下一轮立即重复调用FFmpeg。
     */
    @Test
    void shouldBackOffFailedThumbnail() throws Exception {
        LocalFileMapper mapper = mock(LocalFileMapper.class);
        LocalFileService service = mock(LocalFileService.class);
        LocalFile file = new LocalFile();
        file.setId(99L);
        file.setFilePath("/srv/media/broken.mp4");
        when(mapper.selectThumbnailCandidates(anyString(), anyString(), anyLong(), eq(24)))
                .thenReturn(List.of(file));
        when(service.generateAndPersistThumbnail(99L)).thenThrow(new IOException("broken"));
        ResourceStorageGuard storageGuard = mock(ResourceStorageGuard.class);
        when(storageGuard.isAvailable()).thenReturn(true);
        job = new ThumbnailGenerationJob(mapper, service, storageGuard, mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(job, "scanPath", "/srv");
        ReflectionTestUtils.setField(job, "thumbnailPath", "/srv/.thumbnails");

        job.generateMissingThumbnails();
        job.generateMissingThumbnails();

        verify(service, times(1)).generateAndPersistThumbnail(99L);
    }
}
