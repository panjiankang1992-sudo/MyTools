package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.RegisterArtifactRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.model.RegisterLocationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AssetRegistryServiceTest {

    @Autowired
    private AssetRegistryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldDeduplicateContentAcrossSourcesAndPreserveRelations() {
        String sha256 = "a".repeat(64);
        var firstRequest = new RegisterAssetRequest(7L, "reader-import:1", "READER_EBOOK", "ebook-1",
                sha256, 1024, "text/plain", new RegisterAssetRequest.InitialLocation(
                "reader-location:1", "STORAGE_GATEWAY", "storage://managed/ebooks/1.txt", "v1"));

        var first = service.register(firstRequest);
        var duplicate = service.register(firstRequest);
        var secondSource = service.register(new RegisterAssetRequest(8L, "download-result:2", "DOWNLOAD",
                "download-2", sha256, 1024, "text/plain", null));
        var thumbnail = service.register(new RegisterAssetRequest(7L, "media-thumbnail:1", "MEDIA_ARTIFACT",
                "thumbnail-1", "b".repeat(64), 128, "image/jpeg", null));
        var withArtifact = service.registerArtifact(first.id(), new RegisterArtifactRequest(
                secondSource.version(), thumbnail.id(), "artifact-link:1", "THUMBNAIL", "ffmpeg", "1.0.0"));

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(secondSource.id()).isEqualTo(first.id());
        assertThat(withArtifact.sources()).hasSize(2);
        assertThat(withArtifact.locations()).hasSize(1);
        assertThat(withArtifact.artifacts()).hasSize(1);
        assertThat(withArtifact.artifacts().getFirst().artifactAssetId()).isEqualTo(thumbnail.id());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_outbox", Integer.class)).isEqualTo(7);
    }

    @Test
    void shouldRejectStaleVersionAndChangedIdempotentPayload() {
        var asset = service.register(new RegisterAssetRequest(9L, "source-conflict", "DOWNLOAD", "download-9",
                "c".repeat(64), 256, "application/octet-stream", null));

        assertThatThrownBy(() -> service.registerLocation(asset.id(), new RegisterLocationRequest(
                asset.version() + 1, "location-stale", "LOCAL", "file:///data/file.bin", null)))
                .isInstanceOf(AssetVersionConflictException.class);
        assertThatThrownBy(() -> service.register(new RegisterAssetRequest(9L, "source-conflict", "DOWNLOAD",
                "download-9", "d".repeat(64), 256, "application/octet-stream", null)))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
