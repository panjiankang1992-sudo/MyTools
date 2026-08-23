package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资源盘健康检查和数据清理保护测试。
 */
class ResourceStorageGuardTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 验证根目录、媒体和缩略图目录都可读时允许清理。
     */
    @Test
    void shouldAllowCleanupWhenStorageStructureIsHealthy() throws Exception {
        Path root = temporaryDirectory.resolve("resource");
        Files.createDirectories(root.resolve("media"));
        Files.createDirectories(root.resolve(".thumbnails"));
        ResourceStorageGuard guard = createGuard(root);

        ResourceStorageGuard.StorageStatus status = guard.status();

        assertThat(status.available()).isTrue();
        guard.requireAvailableForCleanup(root.resolve("media"));
    }

    /**
     * 验证资源目录结构不完整时禁止数据库历史路径清理。
     */
    @Test
    void shouldBlockCleanupWhenStorageStructureIsMissing() throws Exception {
        Path root = temporaryDirectory.resolve("resource");
        Files.createDirectories(root.resolve("media"));
        ResourceStorageGuard guard = createGuard(root);

        assertThat(guard.status().reason()).isEqualTo("thumbnails-missing");
        assertThatThrownBy(() -> guard.requireAvailableForCleanup(root.resolve("media")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("30012");
    }

    private ResourceStorageGuard createGuard(Path root) {
        ResourceStorageGuard guard = new ResourceStorageGuard();
        ReflectionTestUtils.setField(guard, "scanPath", root.toString());
        ReflectionTestUtils.setField(guard, "thumbnailPath", root.resolve(".thumbnails").toString());
        ReflectionTestUtils.setField(guard, "mountPath", "");
        ReflectionTestUtils.setField(guard, "requireMount", false);
        return guard;
    }
}
