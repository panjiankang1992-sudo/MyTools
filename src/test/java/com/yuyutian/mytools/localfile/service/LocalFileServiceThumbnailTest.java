package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地媒体缩略图恢复测试。
 */
class LocalFileServiceThumbnailTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 验证失败任务遗留的空JPEG会被重新生成，而不是作为有效缓存返回。
     */
    @Test
    void shouldReplaceEmptyThumbnailCache() throws Exception {
        Path mediaRoot = temporaryDirectory.resolve("media");
        Path imagePath = mediaRoot.resolve("photo.png");
        Path thumbnailRoot = temporaryDirectory.resolve("thumbnails");
        Files.createDirectories(mediaRoot);
        Files.createDirectories(thumbnailRoot);
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        ImageIO.write(image, "png", imagePath.toFile());

        Path emptyThumbnail = thumbnailRoot.resolve("21.jpg");
        Files.createFile(emptyThumbnail);
        Files.setLastModifiedTime(emptyThumbnail, FileTime.from(Instant.now().plusSeconds(10)));

        LocalFile file = new LocalFile();
        file.setId(21L);
        file.setFilePath(imagePath.toString());
        LocalDirectory directory = new LocalDirectory();
        directory.setId(7L);
        directory.setDirectoryPath(mediaRoot.toString());
        LocalFileMapper fileMapper = mock(LocalFileMapper.class);
        LocalDirectoryMapper directoryMapper = mock(LocalDirectoryMapper.class);
        when(fileMapper.selectById(21L)).thenReturn(file);
        when(directoryMapper.selectAll()).thenReturn(List.of(directory));

        LocalFileService service = new LocalFileService(fileMapper, mock(FileTagMapper.class), directoryMapper,
                mock(TaggerService.class),
                mock(com.yuyutian.mytools.media.service.importer.MediaPackageTagImportService.class));
        ReflectionTestUtils.setField(service, "scanPath", mediaRoot.toString());
        ReflectionTestUtils.setField(service, "thumbnailPath", thumbnailRoot.toString());

        Path result = service.getThumbnailFilePath(21L);

        assertTrue(Files.size(result) > 2);
        assertTrue(ImageIO.read(result.toFile()).getWidth() > 0);
    }
}
