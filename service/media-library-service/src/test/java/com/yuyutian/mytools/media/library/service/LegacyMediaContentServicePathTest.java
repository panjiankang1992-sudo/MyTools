package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.LegacyContentDatabase;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 旧媒体真实路径归一化测试。
 */
class LegacyMediaContentServicePathTest {

    @Test
    void keepsCurrentUserMediaHierarchyWithoutDuplicatingUsername() {
        LegacyMediaContentService service = service();

        Path resolved = service.resolveMigratedPath(Path.of(
                "/opt/extend/resource/yuyutian/media/202608/20260825/image.jpg"));

        assertEquals(Path.of("/opt/extend/resource/yuyutian/media/202608/20260825/image.jpg"), resolved);
    }

    @Test
    void upgradesLegacyGlobalMediaPathIntoCurrentUserHierarchy() {
        LegacyMediaContentService service = service();

        Path resolved = service.resolveMigratedPath(Path.of(
                "/opt/extend/resource/media/202608/20260825/image.jpg"));

        assertEquals(Path.of("/opt/extend/resource/yuyutian/media/202608/20260825/image.jpg"), resolved);
    }

    private LegacyMediaContentService service() {
        return new LegacyMediaContentService(mock(MediaRepository.class), mock(LegacyContentDatabase.class),
                mock(DerivedThumbnailContentService.class), "yuyutian");
    }
}
