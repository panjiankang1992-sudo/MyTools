package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.localfile.mapper.FileMaintenanceLogMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 文件维护服务测试。
 */
class FileMaintenanceServiceTest {

    private FileMaintenanceService service;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        service = new FileMaintenanceService(mock(LocalFileMapper.class), mock(LocalDirectoryMapper.class),
                mock(FileMaintenanceLogMapper.class), mock(TaggerClient.class), directExecutor);
    }

    @Test
    void shouldRemoveSourceWebsiteAndEditionNoise() {
        String cleaned = ReflectionTestUtils.invokeMethod(service, "deterministicCleanName",
                "1084_soushu2023_com@仙宫香妃录_排版_搜书吧_搜书吧_1.txt");

        assertThat(cleaned).isEqualTo("仙宫香妃录_1.txt");
    }

    @Test
    void shouldRemoveBotHashAndEmbeddedTags() {
        String botCleaned = ReflectionTestUtils.invokeMethod(service, "deterministicCleanName",
                "DownloadBot-PikPak-联调测试--e48437ffe01c.txt");
        String tagCleaned = ReflectionTestUtils.invokeMethod(service, "deterministicCleanName",
                "书名_tags_标签一,标签二_user.txt");

        assertThat(botCleaned).isEqualTo("联调测试.txt");
        assertThat(tagCleaned).isEqualTo("书名.txt");
    }

    @Test
    void shouldRejectModelSuggestionThatReplacesTitle() {
        Boolean accepted = ReflectionTestUtils.invokeMethod(service, "isDeletionOnlySuggestion",
                "原始书名_排版.txt", "另一部书.txt");
        Boolean cleaned = ReflectionTestUtils.invokeMethod(service, "isDeletionOnlySuggestion",
                "网站_原始书名_排版.txt", "原始书名.txt");

        assertThat(accepted).isFalse();
        assertThat(cleaned).isTrue();
    }

    @Test
    void shouldKeepOnlyQuotedTitleAndStoreOriginalFilename(@TempDir Path temporaryDirectory) throws Exception {
        String originalName = "《示例书名》排版01_38完本_作品作者：作者.txt";
        String cleaned = ReflectionTestUtils.invokeMethod(service, "titleOnlyCleanName",
                originalName, "示例书名.txt");
        Path file = temporaryDirectory.resolve(originalName);
        Files.writeString(file, "正文内容");

        Boolean changed = ReflectionTestUtils.invokeMethod(service, "prependOriginalFilename", file, originalName);

        assertThat(cleaned).isEqualTo("示例书名.txt");
        assertThat(changed).isTrue();
        assertThat(Files.readString(file)).startsWith("[Original filename] " + originalName);
    }

    @Test
    void shouldProtectPublishedMediaPackageAssets(@TempDir Path temporaryDirectory) throws Exception {
        Path packageDirectory = Files.createDirectory(temporaryDirectory.resolve("package"));
        Path thumbnail = Files.write(packageDirectory.resolve("thumbnail.jpg"), new byte[] { 1, 2, 3 });
        Files.writeString(packageDirectory.resolve(".ready"), "ready\n");

        Boolean protectedAsset = ReflectionTestUtils.invokeMethod(service,
                "isPublishedMediaPackageAsset", thumbnail, temporaryDirectory);

        assertThat(protectedAsset).isTrue();
    }
}
