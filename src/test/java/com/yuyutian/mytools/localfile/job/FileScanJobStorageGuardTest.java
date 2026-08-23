package com.yuyutian.mytools.localfile.job;

import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.ResourceStorageGuard;
import com.yuyutian.mytools.media.service.importer.MediaPackageTagImportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 定时文件扫描的资源盘保护测试。
 */
class FileScanJobStorageGuardTest {

    /**
     * 验证资源盘不可用时不读写文件数据库。
     */
    @Test
    void shouldSkipDatabaseCleanupWhenStorageIsUnavailable() {
        LocalFileMapper mapper = mock(LocalFileMapper.class);
        ResourceStorageGuard storageGuard = mock(ResourceStorageGuard.class);
        when(storageGuard.isAvailable()).thenReturn(false);
        FileScanJob job = new FileScanJob(mapper, mock(MediaPackageTagImportService.class), storageGuard);
        ReflectionTestUtils.setField(job, "scanPath", "/opt/extend/resource");

        job.scanFiles();

        verifyNoInteractions(mapper);
    }
}
