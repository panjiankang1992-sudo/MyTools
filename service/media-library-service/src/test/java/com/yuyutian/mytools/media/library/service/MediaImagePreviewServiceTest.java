package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.model.MediaModels.MediaView;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用实际转换程序验证尺寸、缓存、并发与权限隔离。 */
class MediaImagePreviewServiceTest {
    @TempDir Path directory;
    private final MediaRepository repository = mock(MediaRepository.class);
    private final LegacyMediaContentService legacy = mock(LegacyMediaContentService.class);
    private final DerivedThumbnailContentService assets = mock(DerivedThumbnailContentService.class);
    private final UUID id = UUID.randomUUID();
    private MediaImagePreviewService service;
    private LegacyMediaContentService.Content original;

    @BeforeEach
    void prepare() throws Exception {
        BufferedImage image = new BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        original = new LegacyMediaContentService.Content(new ByteArrayResource(bytes.toByteArray()), "image/png", bytes.size());
        when(repository.view(id)).thenReturn(Optional.of(media(7, "READY", "a".repeat(64))));
        when(legacy.content(7, id, true)).thenReturn(original);
        when(assets.originalImage(7, id)).thenReturn(Optional.of(original));
        service = new MediaImagePreviewService(repository, legacy, assets, directory.toString(),
                System.getProperty("preview.test.python", "/usr/bin/python3"));
    }

    @Test void shouldResizeAndReuseDiskCacheAcrossServiceInstances() throws Exception {
        var first = service.preview(7, id, 512);
        try (var input = first.resource().getInputStream()) {
            var image = ImageIO.read(input);
            assertThat(image.getWidth()).isEqualTo(512);
            assertThat(image.getHeight()).isBetween(340, 342);
        }
        assertThat(first.sizeBytes()).isLessThan(192 * 1024);
        service = new MediaImagePreviewService(repository, legacy, assets, directory.toString(),
                System.getProperty("preview.test.python", "/usr/bin/python3"));
        assertThat(service.preview(7, id, 512).sizeBytes()).isEqualTo(first.sizeBytes());
        verify(legacy, times(1)).content(7, id, true);
    }

    @Test void shouldUseOriginalForBoundedFullscreenPreview() throws Exception {
        var result = service.preview(7, id, 2048);
        try (var input = result.resource().getInputStream()) {
            assertThat(ImageIO.read(input).getWidth()).isEqualTo(2048);
        }
        assertThat(result.sizeBytes()).isLessThan(1024 * 1024);
        verify(legacy, never()).content(anyLong(), any(), anyBoolean());
    }

    @Test void shouldCheckOwnershipAndStatusEvenAfterCacheHit() {
        service.preview(7, id, 512);
        assertThatThrownBy(() -> service.preview(8, id, 512)).isInstanceOf(IllegalArgumentException.class);
        when(repository.view(id)).thenReturn(Optional.of(media(7, "MISSING", "a".repeat(64))));
        assertThatThrownBy(() -> service.preview(7, id, 512)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void shouldInvalidateCacheWhenContentDigestChanges() {
        service.preview(7, id, 512);
        when(repository.view(id)).thenReturn(Optional.of(media(7, "READY", "b".repeat(64))));
        service.preview(7, id, 512);
        verify(legacy, times(2)).content(7, id, true);
    }

    @Test void shouldMergeConcurrentGeneration() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Future<Long>>();
            for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> service.preview(7, id, 512).sizeBytes()));
            for (var task : tasks) assertThat(task.get(10, TimeUnit.SECONDS)).isPositive();
            verify(legacy, times(1)).content(7, id, true);
        } finally { pool.shutdownNow(); }
    }

    @Test void shouldRejectUnsupportedSizeAndInvalidImageWithoutCaching() throws Exception {
        assertThatThrownBy(() -> service.preview(7, id, 4096)).isInstanceOf(IllegalArgumentException.class);
        when(legacy.content(7, id, true)).thenReturn(new LegacyMediaContentService.Content(
                new ByteArrayResource(new byte[]{1, 2, 3}), "image/jpeg", 3));
        assertThatThrownBy(() -> service.preview(7, id, 512)).isInstanceOf(IllegalStateException.class);
        try (var entries = java.nio.file.Files.list(directory)) { assertThat(entries.toList()).isEmpty(); }
    }

    private MediaView media(long owner, String status, String hash) {
        return new MediaView(id, owner, UUID.randomUUID(), "fixture.png", "image/png", original.sizeBytes(),
                hash, status, 1, List.of(), "", "");
    }
}
