package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.LegacyAssetMappingBatch;
import com.yuyutian.mytools.asset.model.LegacyAssetMappingItem;
import com.yuyutian.mytools.asset.model.LegacyAssetMappingLookupRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LegacyAssetMappingMigrationServiceTest {
    @Autowired
    private LegacyAssetMappingMigrationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldDryRunApplyReplayAndRejectChangedLegacyIdentity() {
        LegacyAssetMappingItem item = item("legacy-20", "5".repeat(64), "file:///legacy/20.bin");
        LegacyAssetMappingBatch dryRun = new LegacyAssetMappingBatch(
                "asset-migration-20", "snapshot-20", true, List.of(item));
        long initialRevision = revision();

        var preview = service.migrate(dryRun);
        var applied = service.migrate(new LegacyAssetMappingBatch(
                "asset-migration-20", "snapshot-20", false, List.of(item)));
        var replay = service.migrate(new LegacyAssetMappingBatch(
                "asset-migration-20-replay", "snapshot-20", false, List.of(item)));
        var conflict = service.migrate(new LegacyAssetMappingBatch(
                "asset-migration-20-conflict", "snapshot-20", false,
                List.of(item("legacy-20", "6".repeat(64), "file:///legacy/20.bin"))));

        assertThat(preview.accepted()).isEqualTo(1);
        assertThat(applied.accepted()).isEqualTo(1);
        assertThat(replay.skipped()).isEqualTo(1);
        assertThat(conflict.rejected()).isEqualTo(1);
        assertThat(preview.digestSha256()).isEqualTo(applied.digestSha256());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_legacy_mapping "
                + "WHERE legacy_asset_id='legacy-20'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT source_snapshot_id FROM asset_legacy_mapping "
                + "WHERE legacy_asset_id='legacy-20'", String.class)).isEqualTo("snapshot-20");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_outbox "
                + "WHERE event_type='AssetLegacyMappingRegistered'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(revision()).isGreaterThan(initialRevision);
    }

    @Test
    void shouldValidateWholeRegistrationAndClassifyBatchDuplicates() {
        LegacyAssetMappingItem valid = item("legacy-21", "7".repeat(64), "file:///legacy/21.bin");
        LegacyAssetMappingItem unsafe = item("legacy-22", "8".repeat(64), "https://user:secret@example.com/a");
        var duplicate = service.migrate(new LegacyAssetMappingBatch("asset-migration-21", "snapshot-21", true,
                List.of(valid, valid)));
        var rejected = service.migrate(new LegacyAssetMappingBatch("asset-migration-22", "snapshot-22", true,
                List.of(unsafe)));

        assertThat(duplicate.accepted()).isEqualTo(1);
        assertThat(duplicate.skipped()).isEqualTo(1);
        assertThat(rejected.rejected()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_legacy_mapping "
                + "WHERE legacy_asset_id IN ('legacy-21','legacy-22')", Integer.class)).isZero();
    }

    @Test
    void shouldResolveMappingsAndReportMissingIdentitiesInRequestOrder() {
        LegacyAssetMappingItem item = item("legacy-23", "9".repeat(64), "file:///legacy/23.mp4");
        service.migrate(new LegacyAssetMappingBatch(
                "asset-migration-23", "snapshot-23", false, List.of(item)));
        var existing = new LegacyAssetMappingLookupRequest.Identity("MyTools", "legacy-23");
        var missing = new LegacyAssetMappingLookupRequest.Identity("MyTools", "legacy-24");

        var result = service.resolve(new LegacyAssetMappingLookupRequest(List.of(existing, missing)));

        assertThat(result.mappings()).singleElement().satisfies(mapping -> {
            assertThat(mapping.sourceSystem()).isEqualTo("MyTools");
            assertThat(mapping.legacyAssetId()).isEqualTo("legacy-23");
            assertThat(mapping.assetId()).isNotNull();
        });
        assertThat(result.missing()).containsExactly(missing);
        assertThatThrownBy(() -> service.resolve(new LegacyAssetMappingLookupRequest(
                List.of(existing, existing)))).isInstanceOf(IllegalArgumentException.class);
    }

    private LegacyAssetMappingItem item(String legacyId, String sha256, String uri) {
        RegisterAssetRequest request = new RegisterAssetRequest(20L, "legacy-source:" + legacyId,
                "LEGACY_ASSET", legacyId, sha256, 256, "application/octet-stream",
                new RegisterAssetRequest.InitialLocation("legacy-location:" + legacyId,
                        "LEGACY_STORAGE", uri, "v1"));
        return new LegacyAssetMappingItem("MyTools", legacyId, request);
    }

    private long revision() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT revision FROM asset_registry_revision WHERE singleton_id=1", Long.class);
        return value == null ? 0 : value;
    }
}
