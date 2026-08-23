package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.RegisterArtifactRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.model.RegisterLocationRequest;
import com.yuyutian.mytools.asset.model.InvalidateLocationRequest;
import com.yuyutian.mytools.asset.model.PublishBundleRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

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
        int assetCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset", Integer.class);
        int outboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_outbox", Integer.class);
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
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset", Integer.class) - assetCount)
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_outbox", Integer.class) - outboxCount)
                .isEqualTo(7);
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

    @Test
    void shouldInvalidateLocationIdempotentlyAndEmitAuditEvent() {
        var asset = service.register(new RegisterAssetRequest(10L, "location-source:10", "DOWNLOAD", "download-10",
                "e".repeat(64), 512, "application/octet-stream", new RegisterAssetRequest.InitialLocation(
                "location-register:10", "LOCAL", "file:///managed/file-10.bin", "v1")));
        var location = asset.locations().getFirst();
        var request = new InvalidateLocationRequest(asset.version(), "location-invalidate:10", "CHECKSUM_MISMATCH");

        var invalidated = service.invalidateLocation(asset.id(), location.id(), request);
        var replay = service.invalidateLocation(asset.id(), location.id(), request);

        assertThat(invalidated.version()).isEqualTo(asset.version() + 1);
        assertThat(replay.version()).isEqualTo(invalidated.version());
        assertThat(replay.locations().getFirst().availability()).isEqualTo("INVALID");
        assertThat(replay.locations().getFirst().invalidationReason()).isEqualTo("CHECKSUM_MISMATCH");
        assertThat(replay.locations().getFirst().invalidatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_location_invalidation "
                + "WHERE location_id=?", Integer.class, location.id().toString())).isEqualTo(1);
    }

    @Test
    void shouldPublishImmutableSortedBundleAndReplayAfterAssetChanges() {
        var primary = service.register(new RegisterAssetRequest(11L, "bundle-source:11:primary", "MEDIA", "video-11",
                "f".repeat(64), 2048, "video/mp4", null));
        var subtitle = service.register(new RegisterAssetRequest(11L, "bundle-source:11:subtitle", "MEDIA",
                "subtitle-11", "1".repeat(64), 128, "text/vtt", null));
        var request = new PublishBundleRequest(11L, "bundle-publish:11", "media_bundle_11", "bundle",
                List.of(new PublishBundleRequest.Item(subtitle.id(), subtitle.version(), "subs/main.vtt", "SUBTITLE"),
                        new PublishBundleRequest.Item(primary.id(), primary.version(), "media/main.mp4", "PRIMARY")));

        var published = service.publishBundle(request);
        var aliasRequest = new PublishBundleRequest(11L, "bundle-publish-alias:11", "media_bundle_11", "bundle",
                request.items());
        var alias = service.publishBundle(aliasRequest);
        var changed = service.registerLocation(primary.id(), new RegisterLocationRequest(primary.version(),
                "bundle-location:11", "LOCAL", "file:///managed/video-11.mp4", "v1"));
        var replay = service.publishBundle(request);

        assertThat(replay.id()).isEqualTo(published.id());
        assertThat(alias.id()).isEqualTo(published.id());
        assertThat(replay.status()).isEqualTo("PUBLISHED");
        assertThat(replay.manifestSha256()).hasSize(64);
        assertThat(replay.items()).extracting(item -> item.logicalPath())
                .containsExactly("media/main.mp4", "subs/main.vtt");
        assertThat(replay.items().getFirst().assetVersion()).isLessThan(changed.version());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_bundle WHERE id=?",
                Integer.class, published.id().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_bundle_idempotency WHERE bundle_id=?",
                Integer.class, published.id().toString())).isEqualTo(2);
    }

    @Test
    void shouldRejectUnsafeBundlePathsStaleAssetsAndChangedReplay() {
        var asset = service.register(new RegisterAssetRequest(12L, "bundle-source:12", "MEDIA", "video-12",
                "2".repeat(64), 4096, "video/mp4", null));
        var unsafe = new PublishBundleRequest(12L, "bundle-unsafe:12", "unsafe_bundle_12", null,
                List.of(new PublishBundleRequest.Item(asset.id(), asset.version(), "../video.mp4", "PRIMARY")));
        var stale = new PublishBundleRequest(12L, "bundle-stale:12", "stale_bundle_12", null,
                List.of(new PublishBundleRequest.Item(asset.id(), asset.version() + 1, "video.mp4", "PRIMARY")));

        assertThatThrownBy(() -> service.publishBundle(unsafe)).isInstanceOf(AssetInputInvalidException.class);
        assertThatThrownBy(() -> service.publishBundle(stale)).isInstanceOf(BundleManifestConflictException.class);

        var valid = new PublishBundleRequest(12L, "bundle-conflict:12", "valid_bundle_12", null,
                List.of(new PublishBundleRequest.Item(asset.id(), asset.version(), "video.mp4", "PRIMARY")));
        service.publishBundle(valid);
        var changedReplay = new PublishBundleRequest(12L, "bundle-conflict:12", "changed_bundle_12", null,
                List.of(new PublishBundleRequest.Item(asset.id(), asset.version(), "video.mp4", "PRIMARY")));
        assertThatThrownBy(() -> service.publishBundle(changedReplay))
                .isInstanceOf(BundleManifestConflictException.class);
    }

    @Test
    void shouldReturnBoundedDeterministicReconciliationEvidence() {
        long initialRevision = service.reconciliationPage(null, 1).registryRevision();
        service.register(new RegisterAssetRequest(13L, "reconcile-source:13:a", "DOWNLOAD", "download-13-a",
                "3".repeat(64), 64, "application/octet-stream", null));
        service.register(new RegisterAssetRequest(13L, "reconcile-source:13:b", "DOWNLOAD", "download-13-b",
                "4".repeat(64), 65, "application/octet-stream", null));

        var first = service.reconciliationPage(null, 200);
        var replay = service.reconciliationPage(null, 200);
        var bounded = service.reconciliationPage(null, 1);

        assertThat(first.assetCount()).isGreaterThanOrEqualTo(2);
        assertThat(first.sourceCount()).isGreaterThanOrEqualTo(2);
        assertThat(first.registryRevision()).isGreaterThan(initialRevision).isEqualTo(replay.registryRevision());
        assertThat(first.digestSha256()).hasSize(64).isEqualTo(replay.digestSha256());
        assertThat(bounded.assetCount()).isEqualTo(1);
        assertThat(bounded.nextAfterId()).isNotNull();
        assertThatThrownBy(() -> service.reconciliationPage(null, 201))
                .isInstanceOf(AssetInputInvalidException.class);
    }
}
