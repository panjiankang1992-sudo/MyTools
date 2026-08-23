package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.reader.mapper.EbookCatalogMapper;
import com.yuyutian.mytools.reader.model.EbookCatalogItem;
import com.yuyutian.mytools.reader.model.EbookMetadata;
import com.yuyutian.mytools.reader.model.EbookCover;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbookCatalogServiceTest {
    private EbookCatalogMapper catalogMapper;
    private LocalDirectoryMapper directoryMapper;
    private FileTagMapper fileTagMapper;
    private EbookMetadataExtractionService extractionService;
    private EbookCatalogService service;

    @BeforeEach
    void setUp() {
        catalogMapper = mock(EbookCatalogMapper.class);
        directoryMapper = mock(LocalDirectoryMapper.class);
        extractionService = mock(EbookMetadataExtractionService.class);
        LocalDirectory directory = new LocalDirectory();
        directory.setId(2L);
        directory.setDirectoryType("EBOOK");
        directory.setDirectoryPath("/opt/extend/resource/ebook/");
        when(directoryMapper.selectById(2L)).thenReturn(directory);
        fileTagMapper = mock(FileTagMapper.class);
        when(fileTagMapper.selectByFileIds(anyList())).thenReturn(List.of());
        service = new EbookCatalogService(catalogMapper, directoryMapper, fileTagMapper, extractionService);
    }

    /**
     * 验证服务端搜索、成人过滤与分页参数规范化后传递给查询层。
     */
    @Test
    void shouldSearchCatalogWithAdultFilter() {
        when(catalogMapper.selectPage("/opt/extend/resource/ebook", "author", true, 40, 40))
                .thenReturn(List.of());

        var page = service.list(2L, " author ", true, 2, 40);

        assertEquals(2, page.page());
        verify(catalogMapper).selectPage("/opt/extend/resource/ebook", "author", true, 40, 40);
        verify(catalogMapper).count("/opt/extend/resource/ebook", "author", true);
    }

    /**
     * 验证详情接口在过滤开启时不返回已确认成人资源。
     */
    @Test
    void shouldHideAdultDetail() {
        EbookCatalogItem item = new EbookCatalogItem();
        item.setAdultStatus(1);
        item.setAdultContent(true);
        when(catalogMapper.selectById("/opt/extend/resource/ebook", 8L)).thenReturn(item);

        assertThrows(BusinessException.class, () -> service.detail(2L, 8L, true));
    }

    /**
     * 验证封面读取沿用电子书目录和成人过滤校验。
     */
    @Test
    void shouldReadValidatedCover() throws Exception {
        EbookCatalogItem item = new EbookCatalogItem();
        item.setCoverPath("/safe/cover.png");
        when(catalogMapper.selectById("/opt/extend/resource/ebook", 8L)).thenReturn(item);
        EbookCover cover = new EbookCover(new byte[] {1, 2}, "image/png");
        when(extractionService.readCover("/safe/cover.png")).thenReturn(cover);

        assertEquals(cover, service.cover(2L, 8L, true));
    }

    /**
     * 验证增量索引只处理候选文件并返回剩余数量。
     */
    @Test
    void shouldIndexCandidateBatch() {
        LocalFile file = new LocalFile();
        file.setId(11L);
        EbookMetadata metadata = new EbookMetadata();
        metadata.setStatus("READY");
        when(catalogMapper.selectIndexCandidates("/opt/extend/resource/ebook", 2, 20))
                .thenReturn(List.of(file));
        when(extractionService.extract(file)).thenReturn(metadata);
        when(catalogMapper.countIndexCandidates("/opt/extend/resource/ebook", 2)).thenReturn(3L);

        var result = service.index(2L, 20);

        assertEquals(1, result.indexed());
        assertEquals(3, result.remaining());
        verify(catalogMapper).upsert(metadata);
        verify(catalogMapper).deleteOrphans();
        verify(extractionService).cleanupCoverCache(any());
    }

    /**
     * 验证非电子书目录不能用于目录接口。
     */
    @Test
    void shouldRejectNonEbookDirectory() {
        LocalDirectory media = new LocalDirectory();
        media.setDirectoryType("MULTIMEDIA");
        when(directoryMapper.selectById(3L)).thenReturn(media);

        assertThrows(BusinessException.class, () -> service.list(3L, "", false, 1, 40));
    }
}
