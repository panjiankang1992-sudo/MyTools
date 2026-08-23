package com.yuyutian.mytools.asset.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.asset.model.AssetBundleView;
import com.yuyutian.mytools.asset.model.AssetRecord;
import com.yuyutian.mytools.asset.model.AssetView;
import com.yuyutian.mytools.asset.model.InvalidateLocationRequest;
import com.yuyutian.mytools.asset.model.PublishBundleRequest;
import com.yuyutian.mytools.asset.model.RegisterArtifactRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.model.RegisterLocationRequest;
import com.yuyutian.mytools.asset.service.ArtifactCycleException;
import com.yuyutian.mytools.asset.service.AssetNotFoundException;
import com.yuyutian.mytools.asset.service.AssetVersionConflictException;
import com.yuyutian.mytools.asset.service.BundleManifestConflictException;
import com.yuyutian.mytools.asset.service.IdempotencyConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 内容资产、来源、位置和派生关系仓储。
 */
@Repository
public class AssetRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建资产仓储。
     */
    public AssetRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 按内容和来源幂等登记资产。
     */
    public AssetRecord register(RegisterAssetRequest request) {
        Optional<SourceBinding> existing = findSource(request.idempotencyKey());
        if (existing.isPresent()) {
            SourceBinding binding = existing.get();
            validateSourceBinding(binding, request);
            return registerInitialLocation(binding.asset(), request.location());
        }
        Optional<SourceBinding> existingIdentity = findSourceIdentity(request.ownerId(), request.sourceType(),
                request.sourceBusinessId());
        if (existingIdentity.isPresent()) {
            SourceBinding binding = existingIdentity.get();
            validateSourceBinding(binding, request);
            return registerInitialLocation(binding.asset(), request.location());
        }
        AssetRecord asset = findByContent(request.contentSha256().toLowerCase(), request.sizeBytes())
                .orElseGet(() -> insertAsset(request));
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO asset_source
                    (id, asset_id, owner_id, source_type, source_business_id, event_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), asset.id().toString(), request.ownerId(), request.sourceType(),
                request.sourceBusinessId(), request.idempotencyKey(), Timestamp.from(now));
        // 已存在内容增加新来源时才推进版本，新资产的首来源包含在初始版本中。
        if (asset.version() > 1 || !asset.createdAt().equals(asset.updatedAt())) {
            incrementVersion(asset.id(), asset.version());
        } else if (countSources(asset.id()) > 1) {
            incrementVersion(asset.id(), asset.version());
        }
        appendOutbox(asset.id(), "AssetSourceRegistered", Map.of("assetId", asset.id().toString(),
                "sourceType", request.sourceType(), "sourceBusinessId", request.sourceBusinessId()));
        AssetRecord updated = required(asset.id());
        if (request.location() != null) {
            RegisterAssetRequest.InitialLocation location = request.location();
            registerLocation(updated.id(), new RegisterLocationRequest(updated.version(),
                    location.idempotencyKey(), location.providerType(), location.storageUri(),
                    location.providerVersion()));
            updated = required(updated.id());
        }
        return updated;
    }

    /**
     * 按乐观版本幂等登记存储位置。
     */
    public AssetRecord registerLocation(UUID assetId, RegisterLocationRequest request) {
        Optional<LocationBinding> existing = findLocation(request.idempotencyKey());
        if (existing.isPresent()) {
            LocationBinding location = existing.get();
            if (!location.assetId().equals(assetId) || !location.providerType().equals(request.providerType())
                    || !location.storageUri().equals(request.storageUri())) {
                throw new IdempotencyConflictException();
            }
            return required(assetId);
        }
        if (findLocationIdentity(assetId, request.providerType(), request.storageUri()).isPresent()) {
            return required(assetId);
        }
        advanceVersion(assetId, request.expectedAssetVersion());
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO asset_location
                    (id, asset_id, idempotency_key, provider_type, storage_uri, provider_version,
                     availability, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?)
                """, UUID.randomUUID().toString(), assetId.toString(), request.idempotencyKey(),
                request.providerType(), request.storageUri(), request.providerVersion(), Timestamp.from(now),
                Timestamp.from(now));
        appendOutbox(assetId, "AssetLocationRegistered", Map.of("assetId", assetId.toString(),
                "providerType", request.providerType(), "storageUri", request.storageUri()));
        return required(assetId);
    }

    /**
     * 按乐观版本幂等登记派生资产。
     */
    public AssetRecord registerArtifact(UUID parentId, RegisterArtifactRequest request) {
        if (parentId.equals(request.artifactAssetId())) {
            throw new ArtifactCycleException();
        }
        required(request.artifactAssetId());
        Optional<ArtifactBinding> existing = findArtifact(request.idempotencyKey());
        if (existing.isPresent()) {
            ArtifactBinding artifact = existing.get();
            if (!artifact.parentId().equals(parentId)
                    || !artifact.artifactId().equals(request.artifactAssetId())
                    || !artifact.kind().equals(request.artifactKind())) {
                throw new IdempotencyConflictException();
            }
            return required(parentId);
        }
        Optional<ArtifactBinding> existingVersion = findArtifactVersion(parentId, request.artifactKind(),
                request.generatorName(), request.generatorVersion());
        if (existingVersion.isPresent()) {
            if (!existingVersion.get().artifactId().equals(request.artifactAssetId())) {
                throw new IdempotencyConflictException();
            }
            return required(parentId);
        }
        advanceVersion(parentId, request.expectedAssetVersion());
        jdbcTemplate.update("""
                INSERT INTO asset_artifact
                    (id, parent_asset_id, artifact_asset_id, idempotency_key, artifact_kind,
                     generator_name, generator_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), parentId.toString(), request.artifactAssetId().toString(),
                request.idempotencyKey(), request.artifactKind(), request.generatorName(),
                request.generatorVersion(), Timestamp.from(Instant.now()));
        appendOutbox(parentId, "AssetArtifactRegistered", Map.of("assetId", parentId.toString(),
                "artifactAssetId", request.artifactAssetId().toString(), "kind", request.artifactKind()));
        return required(parentId);
    }

    /**
     * 使用乐观版本显式失效一个存储位置。
     */
    public AssetRecord invalidateLocation(UUID assetId, UUID locationId, InvalidateLocationRequest request) {
        Optional<LocationInvalidationBinding> replay = findLocationInvalidation(request.idempotencyKey());
        if (replay.isPresent()) {
            LocationInvalidationBinding binding = replay.get();
            if (!binding.assetId().equals(assetId) || !binding.locationId().equals(locationId)
                    || !binding.reason().equals(request.reason())) {
                throw new IdempotencyConflictException();
            }
            return required(assetId);
        }
        LocationState location = findLocationState(locationId)
                .orElseThrow(() -> new AssetNotFoundException(locationId));
        if (!location.assetId().equals(assetId) || !location.availability().equals("AVAILABLE")) {
            throw new IdempotencyConflictException();
        }
        advanceVersion(assetId, request.expectedAssetVersion());
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE asset_location SET availability='INVALID', invalidation_reason=?, invalidated_at=?,
                    updated_at=? WHERE id=? AND availability='AVAILABLE'
                """, request.reason(), Timestamp.from(now), Timestamp.from(now), locationId.toString());
        if (updated != 1) {
            throw new IdempotencyConflictException();
        }
        jdbcTemplate.update("""
                INSERT INTO asset_location_invalidation
                    (id, asset_id, location_id, idempotency_key, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), assetId.toString(), locationId.toString(),
                request.idempotencyKey(), request.reason(), Timestamp.from(now));
        appendOutbox(assetId, "AssetLocationInvalidated", Map.of("assetId", assetId.toString(),
                "locationId", locationId.toString(), "reason", request.reason()));
        return required(assetId);
    }

    /**
     * 锁定资源包引用的资产并返回版本快照。
     */
    public Map<UUID, AssetRecord> lockBundleAssets(List<PublishBundleRequest.Item> items) {
        Map<UUID, AssetRecord> result = new LinkedHashMap<>();
        List<UUID> assetIds = items.stream().map(PublishBundleRequest.Item::assetId).distinct().sorted().toList();
        for (UUID assetId : assetIds) {
            result.put(assetId, queryAsset("WHERE id = ? FOR UPDATE", assetId.toString())
                    .orElseThrow(() -> new AssetNotFoundException(assetId)));
        }
        return result;
    }

    /**
     * 在校验当前资产版本前解析已发布资源包重放。
     */
    public Optional<UUID> findPublishedBundle(PublishBundleRequest request, String manifestSha256) {
        Optional<BundleBinding> replay = findBundleByIdempotencyKey(request.idempotencyKey());
        if (replay.isPresent()) {
            if (replay.get().ownerId() != request.ownerId()
                    || !replay.get().manifestSha256().equals(manifestSha256)) {
                throw new BundleManifestConflictException();
            }
            return Optional.of(replay.get().id());
        }
        Optional<BundleBinding> existingManifest = findBundleByManifest(request.ownerId(), manifestSha256);
        if (existingManifest.isPresent()) {
            Optional<BundleBinding> concurrentReplay = findBundleByIdempotencyKey(request.idempotencyKey());
            if (concurrentReplay.isPresent()) {
                if (concurrentReplay.get().ownerId() != request.ownerId()
                        || !concurrentReplay.get().manifestSha256().equals(manifestSha256)) {
                    throw new BundleManifestConflictException();
                }
                return Optional.of(concurrentReplay.get().id());
            }
            bindBundleIdempotency(request.idempotencyKey(), existingManifest.get(), Instant.now());
            return Optional.of(existingManifest.get().id());
        }
        return Optional.empty();
    }

    /**
     * 幂等发布不可变资源包。
     */
    public UUID publishBundle(PublishBundleRequest request, List<PublishBundleRequest.Item> items,
                              String manifestSha256) {
        Optional<BundleBinding> replay = findBundleByIdempotencyKey(request.idempotencyKey());
        if (replay.isPresent()) {
            if (replay.get().ownerId() != request.ownerId()
                    || !replay.get().manifestSha256().equals(manifestSha256)) {
                throw new BundleManifestConflictException();
            }
            return replay.get().id();
        }
        Optional<BundleBinding> existingManifest = findBundleByManifest(request.ownerId(), manifestSha256);
        if (existingManifest.isPresent()) {
            Optional<BundleBinding> concurrentReplay = findBundleByIdempotencyKey(request.idempotencyKey());
            if (concurrentReplay.isPresent()) {
                if (concurrentReplay.get().ownerId() != request.ownerId()
                        || !concurrentReplay.get().manifestSha256().equals(manifestSha256)) {
                    throw new BundleManifestConflictException();
                }
                return concurrentReplay.get().id();
            }
            bindBundleIdempotency(request.idempotencyKey(), existingManifest.get(), Instant.now());
            return existingManifest.get().id();
        }
        UUID bundleId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO asset_bundle
                    (id, owner_id, idempotency_key, name, description, manifest_sha256, status,
                     created_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', ?, ?)
                """, bundleId.toString(), request.ownerId(), request.idempotencyKey(), request.name(),
                request.description(), manifestSha256, Timestamp.from(now), Timestamp.from(now));
        bindBundleIdempotency(request.idempotencyKey(),
                new BundleBinding(bundleId, request.ownerId(), manifestSha256), now);
        int sequence = 0;
        for (PublishBundleRequest.Item item : items) {
            jdbcTemplate.update("""
                    INSERT INTO asset_bundle_item
                        (id, bundle_id, asset_id, asset_version, logical_path, item_role,
                         sequence_number, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), bundleId.toString(), item.assetId().toString(),
                    item.expectedAssetVersion(), item.logicalPath(), item.role(), sequence++, Timestamp.from(now));
        }
        appendOutbox(bundleId, "AssetBundlePublished", Map.of("bundleId", bundleId.toString(),
                "ownerId", request.ownerId(), "manifestSha256", manifestSha256, "itemCount", items.size()));
        return bundleId;
    }

    /**
     * 查询已发布资源包及固定清单。
     */
    public AssetBundleView bundleView(UUID bundleId) {
        BundleRecord bundle = jdbcTemplate.query("SELECT * FROM asset_bundle WHERE id=?",
                (resultSet, rowNumber) -> new BundleRecord(UUID.fromString(resultSet.getString("id")),
                        resultSet.getLong("owner_id"), resultSet.getString("name"),
                        resultSet.getString("description"), resultSet.getString("manifest_sha256"),
                        resultSet.getString("status"), resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("published_at").toInstant()), bundleId.toString())
                .stream().findFirst().orElseThrow(() -> new AssetNotFoundException(bundleId));
        List<AssetBundleView.ItemView> items = jdbcTemplate.query("""
                SELECT asset_id, asset_version, logical_path, item_role, sequence_number
                FROM asset_bundle_item WHERE bundle_id=? ORDER BY sequence_number
                """, (resultSet, rowNumber) -> new AssetBundleView.ItemView(
                UUID.fromString(resultSet.getString("asset_id")), resultSet.getLong("asset_version"),
                resultSet.getString("logical_path"), resultSet.getString("item_role"),
                resultSet.getInt("sequence_number")), bundleId.toString());
        return new AssetBundleView(bundle.id(), bundle.ownerId(), bundle.name(), bundle.description(),
                bundle.manifestSha256(), bundle.status(), items, bundle.createdAt(), bundle.publishedAt());
    }

    /**
     * 按稳定资产标识读取有界对账快照。
     */
    public List<ReconciliationAssetSnapshot> reconciliationPage(UUID afterId, int limit) {
        String clause = afterId == null ? "ORDER BY id LIMIT ?" : "WHERE id > ? ORDER BY id LIMIT ?";
        Object[] arguments = afterId == null ? new Object[]{limit} : new Object[]{afterId.toString(), limit};
        List<AssetRecord> assets = jdbcTemplate.query("SELECT * FROM asset " + clause,
                (resultSet, rowNumber) -> new AssetRecord(UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("content_sha256"), resultSet.getLong("size_bytes"),
                        resultSet.getString("mime_type"), resultSet.getString("status"),
                        resultSet.getLong("version"), resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()), arguments);
        return assets.stream().map(this::reconciliationSnapshot).toList();
    }

    /**
     * 返回所有资产关系写入共享的单调修订号。
     */
    public long registryRevision() {
        Long revision = jdbcTemplate.queryForObject(
                "SELECT revision FROM asset_registry_revision WHERE singleton_id=1", Long.class);
        return revision == null ? 0 : revision;
    }

    /**
     * 按旧系统身份查询资产映射。
     */
    public Optional<LegacyAssetMappingRecord> findLegacyMapping(String sourceSystem, String legacyAssetId) {
        return jdbcTemplate.query("""
                SELECT migration_key, source_snapshot_id, source_system, legacy_asset_id, asset_id,
                    payload_sha256, created_at
                FROM asset_legacy_mapping WHERE source_system=? AND legacy_asset_id=?
                """, (rs, row) -> new LegacyAssetMappingRecord(rs.getString("migration_key"),
                rs.getString("source_snapshot_id"), rs.getString("source_system"), rs.getString("legacy_asset_id"),
                UUID.fromString(rs.getString("asset_id")), rs.getString("payload_sha256"),
                rs.getTimestamp("created_at").toInstant()), sourceSystem, legacyAssetId).stream().findFirst();
    }

    /**
     * 写入旧资产映射及事务事件。
     */
    public void insertLegacyMapping(LegacyAssetMappingRecord mapping) {
        jdbcTemplate.update("""
                INSERT INTO asset_legacy_mapping
                    (id, migration_key, source_snapshot_id, source_system, legacy_asset_id, asset_id,
                     payload_sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), mapping.migrationKey(), mapping.sourceSnapshotId(),
                mapping.sourceSystem(),
                mapping.legacyAssetId(), mapping.assetId().toString(), mapping.payloadSha256(),
                Timestamp.from(mapping.createdAt()));
        appendOutbox(mapping.assetId(), "AssetLegacyMappingRegistered", Map.of(
                "assetId", mapping.assetId().toString(), "sourceSystem", mapping.sourceSystem(),
                "legacyAssetId", mapping.legacyAssetId(), "sourceSnapshotId", mapping.sourceSnapshotId()));
    }

    /**
     * 查询完整资产视图。
     */
    public AssetView view(UUID id) {
        AssetRecord asset = required(id);
        List<AssetView.SourceView> sources = jdbcTemplate.query("""
                SELECT owner_id, source_type, source_business_id, event_key FROM asset_source
                WHERE asset_id = ? ORDER BY created_at, id
                """, (resultSet, rowNumber) -> new AssetView.SourceView(resultSet.getLong("owner_id"),
                resultSet.getString("source_type"), resultSet.getString("source_business_id"),
                resultSet.getString("event_key")), id.toString());
        List<AssetView.LocationView> locations = jdbcTemplate.query("""
                SELECT * FROM asset_location WHERE asset_id = ? ORDER BY created_at, id
                """, (resultSet, rowNumber) -> new AssetView.LocationView(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("provider_type"),
                resultSet.getString("storage_uri"), resultSet.getString("provider_version"),
                resultSet.getString("availability"), resultSet.getString("invalidation_reason"),
                resultSet.getTimestamp("invalidated_at") == null ? null
                        : resultSet.getTimestamp("invalidated_at").toInstant()), id.toString());
        List<AssetView.ArtifactView> artifacts = jdbcTemplate.query("""
                SELECT * FROM asset_artifact WHERE parent_asset_id = ? ORDER BY created_at, id
                """, (resultSet, rowNumber) -> new AssetView.ArtifactView(
                UUID.fromString(resultSet.getString("artifact_asset_id")), resultSet.getString("artifact_kind"),
                resultSet.getString("generator_name"), resultSet.getString("generator_version")), id.toString());
        return new AssetView(asset.id(), asset.contentSha256(), asset.sizeBytes(), asset.mimeType(), asset.status(),
                asset.version(), sources, locations, artifacts, asset.createdAt(), asset.updatedAt());
    }

    private AssetRecord insertAsset(RegisterAssetRequest request) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO asset
                    (id, content_sha256, size_bytes, mime_type, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """, id.toString(), request.contentSha256().toLowerCase(), request.sizeBytes(), request.mimeType(),
                Timestamp.from(now), Timestamp.from(now));
        appendOutbox(id, "AssetCreated", Map.of("assetId", id.toString(),
                "contentSha256", request.contentSha256().toLowerCase(), "sizeBytes", request.sizeBytes()));
        return required(id);
    }

    private ReconciliationAssetSnapshot reconciliationSnapshot(AssetRecord asset) {
        List<String> sources = jdbcTemplate.query("""
                SELECT owner_id, source_type, source_business_id, event_key FROM asset_source
                WHERE asset_id=? ORDER BY owner_id, source_type, source_business_id, event_key
                """, (rs, row) -> rs.getLong("owner_id") + "\u0000" + rs.getString("source_type") + "\u0000"
                + rs.getString("source_business_id") + "\u0000" + rs.getString("event_key"), asset.id().toString());
        List<String> locations = jdbcTemplate.query("""
                SELECT provider_type, storage_uri, provider_version, availability, invalidation_reason
                FROM asset_location WHERE asset_id=?
                ORDER BY provider_type, storage_uri, id
                """, (rs, row) -> rs.getString("provider_type") + "\u0000" + rs.getString("storage_uri")
                + "\u0000" + nullable(rs.getString("provider_version")) + "\u0000"
                + rs.getString("availability") + "\u0000" + nullable(rs.getString("invalidation_reason")),
                asset.id().toString());
        List<String> artifacts = jdbcTemplate.query("""
                SELECT artifact_asset_id, artifact_kind, generator_name, generator_version
                FROM asset_artifact WHERE parent_asset_id=?
                ORDER BY artifact_kind, generator_name, generator_version, artifact_asset_id
                """, (rs, row) -> rs.getString("artifact_asset_id") + "\u0000" + rs.getString("artifact_kind")
                + "\u0000" + rs.getString("generator_name") + "\u0000" + rs.getString("generator_version"),
                asset.id().toString());
        List<String> bundleReferences = jdbcTemplate.query("""
                SELECT bundle_id, asset_version, logical_path, item_role FROM asset_bundle_item
                WHERE asset_id=? ORDER BY bundle_id, sequence_number
                """, (rs, row) -> rs.getString("bundle_id") + "\u0000" + rs.getLong("asset_version")
                + "\u0000" + rs.getString("logical_path") + "\u0000" + rs.getString("item_role"),
                asset.id().toString());
        List<String> legacyMappings = jdbcTemplate.query("""
                SELECT source_snapshot_id, source_system, legacy_asset_id, payload_sha256
                FROM asset_legacy_mapping
                WHERE asset_id=? ORDER BY source_system, legacy_asset_id
                """, (rs, row) -> rs.getString("source_snapshot_id") + "\u0000"
                + rs.getString("source_system") + "\u0000"
                + rs.getString("legacy_asset_id") + "\u0000" + rs.getString("payload_sha256"),
                asset.id().toString());
        int availableLocationCount = countLocations(asset.id(), "AVAILABLE");
        int invalidLocationCount = countLocations(asset.id(), "INVALID");
        return new ReconciliationAssetSnapshot(asset, sources, locations, artifacts, bundleReferences,
                legacyMappings,
                availableLocationCount, invalidLocationCount);
    }

    private int countLocations(UUID assetId, String availability) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM asset_location WHERE asset_id=? AND availability=?
                """, Integer.class, assetId.toString(), availability);
        return value == null ? 0 : value;
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    private Optional<AssetRecord> findByContent(String sha256, long size) {
        return queryAsset("WHERE content_sha256 = ? AND size_bytes = ?", sha256, size);
    }

    private AssetRecord required(UUID id) {
        return queryAsset("WHERE id = ?", id.toString()).orElseThrow(() -> new AssetNotFoundException(id));
    }

    private Optional<AssetRecord> queryAsset(String clause, Object... args) {
        return jdbcTemplate.query("SELECT * FROM asset " + clause, (resultSet, rowNumber) -> new AssetRecord(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("content_sha256"),
                resultSet.getLong("size_bytes"), resultSet.getString("mime_type"), resultSet.getString("status"),
                resultSet.getLong("version"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()), args).stream().findFirst();
    }

    private Optional<SourceBinding> findSource(String eventKey) {
        return jdbcTemplate.query("""
                SELECT s.owner_id, s.source_type, s.source_business_id, a.* FROM asset_source s
                JOIN asset a ON a.id = s.asset_id WHERE s.event_key = ?
                """, (resultSet, rowNumber) -> new SourceBinding(resultSet.getLong("owner_id"),
                resultSet.getString("source_type"), resultSet.getString("source_business_id"),
                new AssetRecord(UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("content_sha256"), resultSet.getLong("size_bytes"),
                        resultSet.getString("mime_type"), resultSet.getString("status"),
                        resultSet.getLong("version"), resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant())), eventKey).stream().findFirst();
    }

    private Optional<SourceBinding> findSourceIdentity(long ownerId, String sourceType, String businessId) {
        return jdbcTemplate.query("""
                SELECT s.owner_id, s.source_type, s.source_business_id, a.* FROM asset_source s
                JOIN asset a ON a.id = s.asset_id
                WHERE s.owner_id = ? AND s.source_type = ? AND s.source_business_id = ?
                """, (resultSet, rowNumber) -> new SourceBinding(resultSet.getLong("owner_id"),
                resultSet.getString("source_type"), resultSet.getString("source_business_id"),
                new AssetRecord(UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("content_sha256"), resultSet.getLong("size_bytes"),
                        resultSet.getString("mime_type"), resultSet.getString("status"),
                        resultSet.getLong("version"), resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant())), ownerId, sourceType, businessId)
                .stream().findFirst();
    }

    private void validateSourceBinding(SourceBinding binding, RegisterAssetRequest request) {
        if (binding.ownerId() != request.ownerId()
                || !binding.sourceType().equals(request.sourceType())
                || !binding.sourceBusinessId().equals(request.sourceBusinessId())
                || !binding.asset().contentSha256().equalsIgnoreCase(request.contentSha256())
                || binding.asset().sizeBytes() != request.sizeBytes()
                || !binding.asset().mimeType().equalsIgnoreCase(request.mimeType())) {
            throw new IdempotencyConflictException();
        }
    }

    private AssetRecord registerInitialLocation(AssetRecord asset, RegisterAssetRequest.InitialLocation location) {
        if (location == null) {
            return asset;
        }
        return registerLocation(asset.id(), new RegisterLocationRequest(asset.version(), location.idempotencyKey(),
                location.providerType(), location.storageUri(), location.providerVersion()));
    }

    private Optional<LocationBinding> findLocation(String key) {
        return jdbcTemplate.query("""
                SELECT asset_id, provider_type, storage_uri FROM asset_location WHERE idempotency_key = ?
                """, (rs, row) -> new LocationBinding(UUID.fromString(rs.getString("asset_id")),
                rs.getString("provider_type"), rs.getString("storage_uri")), key).stream().findFirst();
    }

    private Optional<LocationBinding> findLocationIdentity(UUID assetId, String providerType, String storageUri) {
        return jdbcTemplate.query("""
                SELECT asset_id, provider_type, storage_uri FROM asset_location
                WHERE asset_id = ? AND provider_type = ? AND storage_uri = ?
                """, (rs, row) -> new LocationBinding(UUID.fromString(rs.getString("asset_id")),
                rs.getString("provider_type"), rs.getString("storage_uri")), assetId.toString(), providerType,
                storageUri).stream().findFirst();
    }

    private Optional<LocationInvalidationBinding> findLocationInvalidation(String key) {
        return jdbcTemplate.query("""
                SELECT asset_id, location_id, reason FROM asset_location_invalidation
                WHERE idempotency_key=?
                """, (rs, row) -> new LocationInvalidationBinding(UUID.fromString(rs.getString("asset_id")),
                UUID.fromString(rs.getString("location_id")), rs.getString("reason")), key)
                .stream().findFirst();
    }

    private Optional<LocationState> findLocationState(UUID locationId) {
        return jdbcTemplate.query("SELECT asset_id, availability FROM asset_location WHERE id=?",
                (rs, row) -> new LocationState(UUID.fromString(rs.getString("asset_id")),
                        rs.getString("availability")), locationId.toString()).stream().findFirst();
    }

    private Optional<BundleBinding> findBundleByIdempotencyKey(String key) {
        return jdbcTemplate.query("""
                SELECT bundle_id, owner_id, manifest_sha256 FROM asset_bundle_idempotency
                WHERE idempotency_key=?
                """, (rs, row) -> new BundleBinding(UUID.fromString(rs.getString("bundle_id")),
                rs.getLong("owner_id"), rs.getString("manifest_sha256")), key).stream().findFirst();
    }

    private Optional<BundleBinding> findBundleByManifest(long ownerId, String manifestSha256) {
        return queryBundleBinding("WHERE owner_id=? AND manifest_sha256=? FOR UPDATE", ownerId, manifestSha256);
    }

    private Optional<BundleBinding> queryBundleBinding(String clause, Object... arguments) {
        return jdbcTemplate.query("SELECT id, owner_id, manifest_sha256 FROM asset_bundle " + clause,
                (rs, row) -> new BundleBinding(UUID.fromString(rs.getString("id")),
                        rs.getLong("owner_id"), rs.getString("manifest_sha256")), arguments)
                .stream().findFirst();
    }

    private void bindBundleIdempotency(String key, BundleBinding bundle, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO asset_bundle_idempotency
                    (idempotency_key, bundle_id, owner_id, manifest_sha256, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, key, bundle.id().toString(), bundle.ownerId(), bundle.manifestSha256(), Timestamp.from(now));
    }

    private Optional<ArtifactBinding> findArtifact(String key) {
        return jdbcTemplate.query("""
                SELECT parent_asset_id, artifact_asset_id, artifact_kind FROM asset_artifact
                WHERE idempotency_key = ?
                """, (rs, row) -> new ArtifactBinding(UUID.fromString(rs.getString("parent_asset_id")),
                UUID.fromString(rs.getString("artifact_asset_id")), rs.getString("artifact_kind")), key)
                .stream().findFirst();
    }

    private Optional<ArtifactBinding> findArtifactVersion(UUID parentId, String kind, String generator,
                                                          String generatorVersion) {
        return jdbcTemplate.query("""
                SELECT parent_asset_id, artifact_asset_id, artifact_kind FROM asset_artifact
                WHERE parent_asset_id = ? AND artifact_kind = ? AND generator_name = ? AND generator_version = ?
                """, (rs, row) -> new ArtifactBinding(UUID.fromString(rs.getString("parent_asset_id")),
                UUID.fromString(rs.getString("artifact_asset_id")), rs.getString("artifact_kind")),
                parentId.toString(), kind, generator, generatorVersion).stream().findFirst();
    }

    private int countSources(UUID id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asset_source WHERE asset_id = ?",
                Integer.class, id.toString());
        return count == null ? 0 : count;
    }

    private void incrementVersion(UUID id, long expected) {
        advanceVersion(id, expected);
    }

    private void advanceVersion(UUID id, long expected) {
        int updated = jdbcTemplate.update("""
                UPDATE asset SET version = version + 1, updated_at = ? WHERE id = ? AND version = ?
                """, Timestamp.from(Instant.now()), id.toString(), expected);
        if (updated == 0) {
            if (queryAsset("WHERE id = ?", id.toString()).isEmpty()) {
                throw new AssetNotFoundException(id);
            }
            throw new AssetVersionConflictException();
        }
    }

    private void appendOutbox(UUID id, String type, Map<String, Object> payload) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO asset_outbox
                        (id, aggregate_id, event_type, payload_json, created_at, published_at)
                    VALUES (?, ?, ?, ?, ?, NULL)
                    """, UUID.randomUUID().toString(), id.toString(), type,
                    objectMapper.writeValueAsString(payload), Timestamp.from(Instant.now()));
            jdbcTemplate.update("""
                    UPDATE asset_registry_revision SET revision=revision+1 WHERE singleton_id=1
                    """);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Asset event cannot be serialized", exception);
        }
    }

    private record SourceBinding(long ownerId, String sourceType, String sourceBusinessId, AssetRecord asset) {
    }

    private record LocationBinding(UUID assetId, String providerType, String storageUri) {
    }

    private record ArtifactBinding(UUID parentId, UUID artifactId, String kind) {
    }

    private record LocationInvalidationBinding(UUID assetId, UUID locationId, String reason) {
    }

    private record LocationState(UUID assetId, String availability) {
    }

    private record BundleBinding(UUID id, long ownerId, String manifestSha256) {
    }

    private record BundleRecord(UUID id, long ownerId, String name, String description,
                                String manifestSha256, String status, Instant createdAt, Instant publishedAt) {
    }

    /**
     * 单资产的内部对账快照。
     */
    public record ReconciliationAssetSnapshot(AssetRecord asset, List<String> sources,
                                               List<String> locations, List<String> artifacts,
                                               List<String> bundleReferences,
                                               List<String> legacyMappings,
                                               int availableLocationCount, int invalidLocationCount) {
    }

    /**
     * 旧资产到新资产的不可变映射审计。
     */
    public record LegacyAssetMappingRecord(String migrationKey, String sourceSnapshotId,
                                           String sourceSystem, String legacyAssetId, UUID assetId,
                                           String payloadSha256, Instant createdAt) {
    }
}
