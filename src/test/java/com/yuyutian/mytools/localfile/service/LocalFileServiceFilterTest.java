package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地媒体分页筛选参数测试。
 */
class LocalFileServiceFilterTest {

    private LocalFileMapper fileMapper;
    private LocalFileService service;

    /**
     * 创建带有多媒体目录的服务实例。
     */
    @BeforeEach
    void setUp() {
        fileMapper = mock(LocalFileMapper.class);
        LocalDirectoryMapper directoryMapper = mock(LocalDirectoryMapper.class);
        LocalDirectory directory = new LocalDirectory();
        directory.setId(7L);
        directory.setDirectoryPath("/srv/media");
        when(directoryMapper.selectById(7L)).thenReturn(directory);
        when(fileMapper.selectPageByDirectory(
                "/srv/media", "album", List.of("travel", "family"), 2,
                true, "VIDEO", "sunset", 40, 40)).thenReturn(List.of());
        service = new LocalFileService(fileMapper, mock(FileTagMapper.class), directoryMapper,
                mock(TaggerService.class),
                mock(com.yuyutian.mytools.media.service.importer.MediaPackageTagImportService.class));
    }

    /**
     * 验证分页查询会去重标签并完整传递全部匹配条件。
     */
    @Test
    void shouldForwardNormalizedMultiTagPageFilter() {
        service.getFilePage(7L, "album", null,
                List.of(" travel ", "family", "travel", ""), true, "VIDEO", " sunset ", 2, 40);

        verify(fileMapper).selectPageByDirectory(
                "/srv/media", "album", List.of("travel", "family"), 2,
                true, "VIDEO", "sunset", 40, 40);
    }

    /**
     * 验证计数查询和分页查询使用相同的多标签条件。
     */
    @Test
    void shouldForwardMultiTagCountFilter() {
        service.countFiles(7L, ".", null, List.of("travel", "family"), false, "MEDIA", " family ");

        verify(fileMapper).countByDirectory(
                "/srv/media", ".", List.of("travel", "family"), 2, false, "MEDIA", "family");
    }
}
