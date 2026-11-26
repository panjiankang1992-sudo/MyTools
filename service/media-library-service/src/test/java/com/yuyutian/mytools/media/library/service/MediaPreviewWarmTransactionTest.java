package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.model.MediaModels.AssetEvent;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;

/** 验证真实入库事务与后台两个预览尺寸的关系。 */
@SpringBootTest(properties = {"media-library.preview-warm-enabled=true","spring.datasource.url=jdbc:h2:mem:preview_warm;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class MediaPreviewWarmTransactionTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
        properties.add("media-library.preview-cache-directory", directory::toString);
    }
    @Autowired MediaLibraryService library;
    @Autowired TransactionTemplate transaction;
    @MockBean MediaImagePreviewService previews;

    AssetEvent image() {
        UUID asset = UUID.randomUUID();
        return new AssetEvent("warm-" + asset, asset, 17L, "DOWNLOAD", asset.toString(), "sample.jpg", "image/jpeg",
                5000, "b".repeat(64), null, null, null);
    }

    @Test void generatesOnlyAfterCommitAndSkipsDuplicates() {
        var rollback = transaction.execute(status -> {
            var media = library.consume(image());
            status.setRollbackOnly();
            return media;
        });
        var event = image();
        var media = library.consume(event);
        library.consume(event);
        verify(previews, timeout(4000).times(1)).preview(17, media.id(), 2048);
        verify(previews).preview(17, media.id(), 512);
        verify(previews, never()).preview(eq(17L), eq(rollback.id()), anyInt());
    }
}
