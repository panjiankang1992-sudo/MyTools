package com.yuyutian.mytools.reader.job;

import com.yuyutian.mytools.reader.model.EbookIndexResult;
import com.yuyutian.mytools.reader.service.EbookCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EbookMetadataIndexJobTest {
    /**
     * 验证启用后按照配置批量执行增量索引。
     */
    @Test
    void shouldRunConfiguredBatch() {
        EbookCatalogService service = mock(EbookCatalogService.class);
        when(service.index(null, 25)).thenReturn(new EbookIndexResult(20, 1, 5));
        EbookMetadataIndexJob job = new EbookMetadataIndexJob(service);
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "batchSize", 25);

        job.indexPendingBooks();

        verify(service).index(null, 25);
    }

    /**
     * 验证关闭配置后不访问数据库。
     */
    @Test
    void shouldSkipWhenDisabled() {
        EbookCatalogService service = mock(EbookCatalogService.class);
        EbookMetadataIndexJob job = new EbookMetadataIndexJob(service);
        ReflectionTestUtils.setField(job, "enabled", false);

        job.indexPendingBooks();

        verifyNoInteractions(service);
    }
}
