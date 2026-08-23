package com.yuyutian.mytools.localfile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.localfile.dto.DirectoryRenameProposal;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerClient;
import com.yuyutian.mytools.media.service.importer.MediaPackageArtifactReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体目录名称净化服务测试。
 */
class DirectoryNameCleanupServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldDeriveMeaningfulMediaNameFromTags() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("media"));
        Path targetDirectory = Files.createDirectories(root.resolve("202608/20260817/15"));
        Path filePath = Files.writeString(targetDirectory.resolve("image.jpg"), "image");
        Fixture fixture = fixture(1L, "MULTIMEDIA", root, filePath,
                List.of(tag(10L, "古代女子"), tag(10L, "汉服")),
                "{\"items\":[{\"id\":\"item-0\",\"semanticName\":\"古装汉服\",\"basis\":\"TAGS\"}]}");

        List<DirectoryRenameProposal> proposals = fixture.service().preview(1L);

        assertEquals(1, proposals.size());
        assertEquals("古装汉服", proposals.getFirst().suggestedName());
        assertEquals("TAGS", proposals.getFirst().basis());
        assertEquals("READY", proposals.getFirst().status());
        assertFalse(proposals.getFirst().needsReview());
    }

    @Test
    void shouldKeepBigMediaTimestampOutsideModelOutput() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("big_media"));
        Path targetDirectory = Files.createDirectory(
                root.resolve("20260817_101500_hhd800.com_DASS-092--b8d29bb91c92"));
        Path filePath = Files.writeString(targetDirectory.resolve("video.mp4"), "video");
        Fixture fixture = fixture(2L, "LARGE_MEDIA", root, filePath,
                List.of(tag(20L, "DASS-092")),
                "{\"items\":[{\"id\":\"item-0\",\"semanticName\":\"DASS-092\",\"basis\":\"ORIGINAL\"}]}");

        DirectoryRenameProposal proposal = fixture.service().preview(2L).getFirst();

        assertEquals("20260817_101500_DASS-092", proposal.suggestedName());
        assertTrue(proposal.targetPath().endsWith("20260817_101500_DASS-092"));
        assertEquals("READY", proposal.status());
    }

    @Test
    void shouldRequireReviewWhenMeaninglessNameHasNoModelResult() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("media-review"));
        Path targetDirectory = Files.createDirectories(root.resolve("202608/20260817/00"));
        Path filePath = Files.writeString(targetDirectory.resolve("unknown.bin"), "unknown");
        Fixture fixture = fixture(3L, "MULTIMEDIA", root, filePath, List.of(), "{\"items\":[]}");

        DirectoryRenameProposal proposal = fixture.service().preview(3L).getFirst();

        assertEquals("REVIEW", proposal.status());
        assertTrue(proposal.needsReview());
        assertEquals("UNCHANGED", proposal.basis());
    }

    @Test
    void shouldRejectModelReplacingMeaningfulSubjectWithTags() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("big-media-subject"));
        Path targetDirectory = Files.createDirectory(root.resolve("20260817_101500_润滑油1"));
        Path filePath = Files.writeString(targetDirectory.resolve("video.mp4"), "video");
        Fixture fixture = fixture(5L, "LARGE_MEDIA", root, filePath,
                List.of(tag(50L, "卡通兔子")),
                "{\"items\":[{\"id\":\"item-0\",\"semanticName\":\"卡通兔子宿舍生活\",\"basis\":\"ORIGINAL\"}]}");

        DirectoryRenameProposal proposal = fixture.service().preview(5L).getFirst();

        assertEquals("20260817_101500_润滑油1", proposal.suggestedName());
        assertEquals("UNCHANGED", proposal.status());
    }

    @Test
    void shouldAtomicallyRenameDirectoryAndUpdateDatabasePrefix() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("big-media-apply"));
        Path source = Files.createDirectory(root.resolve("20260817_101500_mp4_1--abcdef123456"));
        Path filePath = Files.writeString(source.resolve("video.mp4"), "video");
        Fixture fixture = fixture(4L, "LARGE_MEDIA", root, filePath,
                List.of(tag(40L, "蓝发洛丽塔")),
                "{\"items\":[{\"id\":\"item-0\",\"semanticName\":\"蓝发洛丽塔\",\"basis\":\"TAGS\"}]}");
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(fixture.transactionTemplate()).executeWithoutResult(any());
        DirectoryRenameProposal proposal = fixture.service().preview(4L).getFirst();

        int renamed = fixture.service().apply(4L, List.of(proposal));

        Path target = root.resolve("20260817_101500_蓝发洛丽塔");
        assertEquals(1, renamed);
        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(target.resolve("video.mp4")));
        verify(fixture.fileMapper()).replaceDirectoryPrefix(eq(source.toString()), eq(target.toString()), any());
    }

    private Fixture fixture(Long directoryId, String directoryType, Path root, Path filePath,
                            List<FileTag> tags, String modelResponse) throws Exception {
        LocalDirectoryMapper directoryMapper = mock(LocalDirectoryMapper.class);
        LocalFileMapper fileMapper = mock(LocalFileMapper.class);
        FileTagMapper tagMapper = mock(FileTagMapper.class);
        TaggerClient taggerClient = mock(TaggerClient.class);
        MediaPackageArtifactReader artifactReader = mock(MediaPackageArtifactReader.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();

        LocalDirectory directory = new LocalDirectory();
        directory.setId(directoryId);
        directory.setDirectoryType(directoryType);
        directory.setDirectoryPath(root.toString());
        when(directoryMapper.selectById(directoryId)).thenReturn(directory);

        LocalFile file = new LocalFile();
        file.setId(tags.isEmpty() ? 30L : tags.getFirst().getFileId());
        file.setFilePath(filePath.toString());
        when(fileMapper.selectActiveFilesByDirectory(root.toString())).thenReturn(List.of(file));
        when(tagMapper.selectByFileIds(anyList())).thenReturn(tags);
        when(taggerClient.analyzeJson(anyString())).thenReturn(objectMapper.readTree(modelResponse));

        DirectoryNameCleanupService service = new DirectoryNameCleanupService(directoryMapper, fileMapper, tagMapper,
                taggerClient, artifactReader, objectMapper, transactionTemplate);
        return new Fixture(service, fileMapper, transactionTemplate);
    }

    private FileTag tag(Long fileId, String name) {
        FileTag tag = new FileTag();
        tag.setFileId(fileId);
        tag.setTagName(name);
        return tag;
    }

    private record Fixture(DirectoryNameCleanupService service, LocalFileMapper fileMapper,
                           TransactionTemplate transactionTemplate) {
    }
}
