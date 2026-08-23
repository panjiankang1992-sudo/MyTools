package com.yuyutian.mytools.asset.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.asset.model.AssetRecord;
import com.yuyutian.mytools.asset.model.AssetView;
import com.yuyutian.mytools.asset.model.RegisterArtifactRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.model.RegisterLocationRequest;
import com.yuyutian.mytools.asset.service.ArtifactCycleException;
import com.yuyutian.mytools.asset.service.AssetNotFoundException;
import com.yuyutian.mytools.asset.service.AssetVersionConflictException;
import com.yuyutian.mytools.asset.service.IdempotencyConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
                resultSet.getString("availability")), id.toString());
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
}
