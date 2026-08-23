package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地媒体历史路径恢复测试。
 */
class LocalFileServicePathTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 验证旧服务器绝对路径可按分类目录后的相对路径恢复。
     */
    @Test
    void shouldResolveLegacyPathInsideConfiguredMediaRoot() throws Exception {
        Path scanRoot = temporaryDirectory.resolve("OpenClaw");
        Path actualFile = scanRoot.resolve("media/album/photo.jpg");
        Files.createDirectories(actualFile.getParent());
        Files.writeString(actualFile, "image");

        LocalFile file = new LocalFile();
        file.setId(1L);
        file.setFilePath("/legacy/server/OpenClaw/media/album/photo.jpg");
        LocalFileMapper fileMapper = mock(LocalFileMapper.class);
        LocalDirectoryMapper directoryMapper = mock(LocalDirectoryMapper.class);
        when(fileMapper.selectById(1L)).thenReturn(file);
        when(directoryMapper.selectAll()).thenReturn(List.of());

        LocalFileService service = new LocalFileService(fileMapper, mock(FileTagMapper.class), directoryMapper,
                mock(TaggerService.class),
                mock(com.yuyutian.mytools.media.service.importer.MediaPackageTagImportService.class),
                mock(ResourceStorageGuard.class));
        ReflectionTestUtils.setField(service, "scanPath", scanRoot.toString());

        assertEquals(actualFile.toAbsolutePath().normalize(), service.getReadableFilePath(1L));
    }
}
