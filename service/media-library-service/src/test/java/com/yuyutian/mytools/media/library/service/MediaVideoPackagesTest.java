package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.InternalToken;
import com.yuyutian.mytools.media.library.model.MediaModels.MediaView;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证已发布副本的权限、版本、清单和回退。 */
class MediaVideoPackagesTest {
    @TempDir Path root;
    final UUID id = UUID.randomUUID();
    final UUID asset = UUID.randomUUID();
    final MediaRepository repo = mock(MediaRepository.class);
    MediaView media(String hash, String status) {
        return new MediaView(id, 8, asset, "sample", "video/mp4", 105L * 1024 * 1024,
                hash.repeat(64), status, 1, List.of(), "", "");
    }

    @Test void onlyReadyCurrentOwnerMayReadCompletePackage() throws Exception {
        var packages = new MediaVideoPackages(repo, new InternalToken("test"), root, 1, true, id.toString());
        when(repo.view(id)).thenReturn(Optional.of(media("a", "READY")));
        Path dir = Files.createDirectory(root.resolve("8-" + id + "-" + "a".repeat(64)));
        Path segment = Files.write(dir.resolve("v480-00001.ts"), new byte[]{1, 2});
        try {
            Files.writeString(dir.resolve("master.m3u8"), "#EXTM3U");
            assertThat(packages.prepare(8, id)).isEqualTo("master.m3u8");
            assertThat(packages.part(8, id, segment.getFileName().toString())).isEqualTo(segment);
            assertThatThrownBy(() -> packages.part(7, id, "master.m3u8")).hasMessageContaining("404");
            assertThatThrownBy(() -> packages.part(8, id, "../source")).hasMessageContaining("404");
            when(repo.view(id)).thenReturn(Optional.of(media("b", "READY")));
            assertThatThrownBy(() -> packages.part(8, id, "master.m3u8")).hasMessageContaining("404");
            when(repo.view(id)).thenReturn(Optional.of(media("a", "MISSING")));
            assertThatThrownBy(() -> packages.part(8, id, segment.getFileName().toString())).hasMessageContaining("404");
        } finally { packages.close(); }
    }

    @Test void pilotLeavesOtherMediaOnOriginal() {
        var packages = new MediaVideoPackages(repo, new InternalToken("test"), root, 1, true, UUID.randomUUID().toString());
        when(repo.view(id)).thenReturn(Optional.of(media("a", "READY")));
        try {
            assertThat(packages.prepare(8, id)).isEqualTo("original");
            assertThatThrownBy(() -> packages.part(8, id, "master.m3u8")).hasMessageContaining("404");
        } finally { packages.close(); }
    }

    @Test void disabledPackagesAlwaysUseOriginal() {
        var packages = new MediaVideoPackages(repo, new InternalToken("test"), root, 1, false, "");
        when(repo.view(id)).thenReturn(Optional.of(media("a", "READY")));
        try {
            assertThat(packages.prepare(8, id)).isEqualTo("original");
            assertThatThrownBy(() -> packages.part(8, id, "master.m3u8")).hasMessageContaining("404");
        } finally { packages.close(); }
    }
}
