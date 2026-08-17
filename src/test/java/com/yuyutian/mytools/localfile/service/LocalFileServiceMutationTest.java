package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地媒体文件变更测试。
 */
class LocalFileServiceMutationTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 验证重命名同时更新物理文件和索引路径。
     */
    @Test
    void shouldRenameManagedFileAndUpdateIndex() throws Exception {
        MutationFixture fixture = fixture("original.mp3");
        Path target = fixture.root().resolve("renamed.mp3");

        fixture.service().renameFile(41L, "renamed.mp3");

        assertFalse(Files.exists(fixture.source()));
        assertTrue(Files.isRegularFile(target));
        verify(fixture.fileMapper()).updateFileLocation(eq(41L), eq("renamed.mp3"),
                eq(target.toString()), any(LocalDateTime.class));
    }

    /**
     * 验证移动只允许进入同一受管媒体根目录中的现有目录。
     */
    @Test
    void shouldMoveManagedFileInsideRootAndUpdateIndex() throws Exception {
        MutationFixture fixture = fixture("move.mp3");
        Path targetDirectory = fixture.root().resolve("archive");
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve("move.mp3");

        fixture.service().moveFile(41L, "/archive");

        assertFalse(Files.exists(fixture.source()));
        assertTrue(Files.isRegularFile(target));
        verify(fixture.fileMapper()).updateFileLocation(eq(41L), eq("move.mp3"),
                eq(target.toString()), any(LocalDateTime.class));
    }

    /**
     * 验证人工标签按去重后的完整集合原子替换。
     */
    @Test
    void shouldReplaceTagsWithNormalizedUniqueValues() throws Exception {
        MutationFixture fixture = fixture("tags.mp3");

        List<FileTag> tags = fixture.service().replaceFileTags(41L,
                List.of(" travel ", "favorite", "travel"));

        assertEquals(List.of("travel", "favorite"), tags.stream().map(FileTag::getTagName).toList());
        assertTrue(tags.stream().allMatch(tag -> "user".equals(tag.getTagType())
                && Double.valueOf(1D).equals(tag.getConfidence())));
        verify(fixture.fileTagMapper()).deleteByFileId(41L);
        verify(fixture.fileTagMapper()).batchInsert(argThat(inserted -> inserted.size() == 2
                && "travel".equals(inserted.get(0).getTagName())
                && "favorite".equals(inserted.get(1).getTagName())));
    }

    /**
     * 验证删除物理文件后软删除对应索引记录。
     */
    @Test
    void shouldDeleteManagedFileAndMarkIndexDeleted() throws Exception {
        MutationFixture fixture = fixture("delete.mp3");

        fixture.service().deleteFile(41L);

        assertFalse(Files.exists(fixture.source()));
        verify(fixture.fileMapper()).markDeletedByIds(eq(List.of(41L)), any(LocalDateTime.class));
    }

    private MutationFixture fixture(String filename) throws Exception {
        Path root = temporaryDirectory.resolve(filename.replace('.', '-'));
        Files.createDirectories(root);
        Path source = root.resolve(filename);
        Files.writeString(source, "media");

        LocalFile file = new LocalFile();
        file.setId(41L);
        file.setFilename(filename);
        file.setFilePath(source.toString());
        LocalDirectory directory = new LocalDirectory();
        directory.setId(7L);
        directory.setDirectoryPath(root.toString());

        LocalFileMapper fileMapper = mock(LocalFileMapper.class);
        FileTagMapper fileTagMapper = mock(FileTagMapper.class);
        LocalDirectoryMapper directoryMapper = mock(LocalDirectoryMapper.class);
        when(fileMapper.selectById(41L)).thenReturn(file);
        when(directoryMapper.selectAll()).thenReturn(List.of(directory));

        LocalFileService service = new LocalFileService(fileMapper, fileTagMapper, directoryMapper,
                mock(TaggerService.class),
                mock(com.yuyutian.mytools.media.service.importer.MediaPackageTagImportService.class));
        ReflectionTestUtils.setField(service, "scanPath", root.toString());
        return new MutationFixture(service, fileMapper, fileTagMapper, root, source);
    }

    private record MutationFixture(LocalFileService service, LocalFileMapper fileMapper,
                                   FileTagMapper fileTagMapper, Path root, Path source) {
    }
}
