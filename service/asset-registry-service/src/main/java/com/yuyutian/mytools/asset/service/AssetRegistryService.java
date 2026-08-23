package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.AssetView;
import com.yuyutian.mytools.asset.model.AssetBundleView;
import com.yuyutian.mytools.asset.model.AssetRecord;
import com.yuyutian.mytools.asset.model.AssetReconciliationPage;
import com.yuyutian.mytools.asset.model.InvalidateLocationRequest;
import com.yuyutian.mytools.asset.model.PublishBundleRequest;
import com.yuyutian.mytools.asset.model.RegisterArtifactRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.model.RegisterLocationRequest;
import com.yuyutian.mytools.asset.repository.AssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 内容资产身份、来源、位置和派生关系原子服务。
 */
@Service
public class AssetRegistryService {

    private final AssetRepository repository;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建资产注册服务。
     */
    public AssetRegistryService(AssetRepository repository, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 按内容和来源幂等登记资产。
     */
    public AssetView register(RegisterAssetRequest request) {
        if (request.location() != null) {
            validateStorageUri(request.location().storageUri());
        }
        var asset = transactionTemplate.execute(status -> repository.register(request));
        if (asset == null) {
            throw new IllegalStateException("Asset transaction returned no record");
        }
        return repository.view(asset.id());
    }

    /**
     * 使用乐观版本登记存储位置。
     */
    public AssetView registerLocation(UUID id, RegisterLocationRequest request) {
        validateStorageUri(request.storageUri());
        var asset = transactionTemplate.execute(status -> repository.registerLocation(id, request));
        if (asset == null) {
            throw new IllegalStateException("Asset location transaction returned no record");
        }
        return repository.view(asset.id());
    }

    /**
     * 使用乐观版本登记派生资产关系。
     */
    public AssetView registerArtifact(UUID id, RegisterArtifactRequest request) {
        var asset = transactionTemplate.execute(status -> repository.registerArtifact(id, request));
        if (asset == null) {
            throw new IllegalStateException("Asset artifact transaction returned no record");
        }
        return repository.view(asset.id());
    }

    /**
     * 使用乐观版本显式失效一个存储位置。
     */
    public AssetView invalidateLocation(UUID id, UUID locationId, InvalidateLocationRequest request) {
        var asset = transactionTemplate.execute(status -> repository.invalidateLocation(id, locationId, request));
        if (asset == null) {
            throw new IllegalStateException("Asset location invalidation returned no record");
        }
        return repository.view(asset.id());
    }

    /**
     * 校验资产版本并原子发布不可变资源包。
     */
    public AssetBundleView publishBundle(PublishBundleRequest request) {
        List<PublishBundleRequest.Item> items = request.items().stream()
                .sorted(Comparator.comparing(PublishBundleRequest.Item::logicalPath)).toList();
        validateBundlePaths(items);
        String manifestSha256 = manifestDigest(request, items);
        UUID bundleId = transactionTemplate.execute(status -> {
            UUID replay = repository.findPublishedBundle(request, manifestSha256).orElse(null);
            if (replay != null) {
                return replay;
            }
            Map<UUID, AssetRecord> assets = repository.lockBundleAssets(items);
            // 获取资产锁后再次检查，折叠等待期间完成的并发发布。
            replay = repository.findPublishedBundle(request, manifestSha256).orElse(null);
            if (replay != null) {
                return replay;
            }
            for (PublishBundleRequest.Item item : items) {
                AssetRecord asset = assets.get(item.assetId());
                if (asset == null) {
                    throw new AssetNotFoundException(item.assetId());
                }
                if (asset.version() != item.expectedAssetVersion()) {
                    throw new BundleManifestConflictException();
                }
            }
            return repository.publishBundle(request, items, manifestSha256);
        });
        if (bundleId == null) {
            throw new IllegalStateException("Asset bundle transaction returned no record");
        }
        return repository.bundleView(bundleId);
    }

    /**
     * 查询已发布资源包。
     */
    public AssetBundleView getBundle(UUID bundleId) {
        return repository.bundleView(bundleId);
    }

    /**
     * 返回有界资产关系数量和确定性摘要，供对账任务分页调用。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssetReconciliationPage reconciliationPage(UUID afterId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new AssetInputInvalidException();
        }
        List<AssetRepository.ReconciliationAssetSnapshot> values = repository
                .reconciliationPage(afterId, limit + 1);
        List<AssetRepository.ReconciliationAssetSnapshot> page = values.stream().limit(limit).toList();
        MessageDigest pageDigest = digest();
        int sourceCount = 0;
        int availableLocationCount = 0;
        int invalidLocationCount = 0;
        int artifactCount = 0;
        int bundleReferenceCount = 0;
        int legacyMappingCount = 0;
        for (AssetRepository.ReconciliationAssetSnapshot snapshot : page) {
            AssetRecord asset = snapshot.asset();
            update(pageDigest, asset.id().toString(), asset.contentSha256(), Long.toString(asset.sizeBytes()),
                    asset.mimeType(), asset.status(), Long.toString(asset.version()));
            snapshot.sources().forEach(value -> update(pageDigest, "SOURCE", value));
            snapshot.locations().forEach(value -> update(pageDigest, "LOCATION", value));
            snapshot.artifacts().forEach(value -> update(pageDigest, "ARTIFACT", value));
            snapshot.bundleReferences().forEach(value -> update(pageDigest, "BUNDLE", value));
            snapshot.legacyMappings().forEach(value -> update(pageDigest, "LEGACY", value));
            sourceCount += snapshot.sources().size();
            availableLocationCount += snapshot.availableLocationCount();
            invalidLocationCount += snapshot.invalidLocationCount();
            artifactCount += snapshot.artifacts().size();
            bundleReferenceCount += snapshot.bundleReferences().size();
            legacyMappingCount += snapshot.legacyMappings().size();
        }
        String nextAfterId = values.size() > limit && !page.isEmpty()
                ? page.getLast().asset().id().toString() : null;
        return new AssetReconciliationPage(nextAfterId, repository.registryRevision(), page.size(), sourceCount,
                availableLocationCount,
                invalidLocationCount, artifactCount, bundleReferenceCount,
                legacyMappingCount,
                HexFormat.of().formatHex(pageDigest.digest()));
    }

    /**
     * 查询完整资产视图。
     */
    public AssetView get(UUID id) {
        return repository.view(id);
    }

    private void validateStorageUri(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getScheme().isBlank() || uri.getUserInfo() != null) {
                throw new AssetInputInvalidException();
            }
        } catch (URISyntaxException exception) {
            throw new AssetInputInvalidException();
        }
    }

    private void validateBundlePaths(List<PublishBundleRequest.Item> items) {
        Set<String> paths = new HashSet<>();
        for (PublishBundleRequest.Item item : items) {
            String path = item.logicalPath();
            String[] segments = path.split("/", -1);
            if (path.startsWith("/") || path.contains("\\")
                    || path.chars().anyMatch(Character::isISOControl) || !paths.add(path)) {
                throw new AssetInputInvalidException();
            }
            for (String segment : segments) {
                if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                    throw new AssetInputInvalidException();
                }
            }
        }
    }

    private String manifestDigest(PublishBundleRequest request, List<PublishBundleRequest.Item> items) {
        MessageDigest digest = digest();
        update(digest, Long.toString(request.ownerId()), request.name(), request.description());
        for (PublishBundleRequest.Item item : items) {
            update(digest, item.assetId().toString(), Long.toString(item.expectedAssetVersion()),
                    item.logicalPath(), item.role());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void update(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
