package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.LegacyAssetMappingBatch;
import com.yuyutian.mytools.asset.model.LegacyAssetMappingItem;
import com.yuyutian.mytools.asset.model.LegacyAssetMappingResult;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.repository.AssetRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 预演或幂等迁移旧资产身份，并复用标准资产登记规则。
 */
@Service
public class LegacyAssetMappingMigrationService {
    private final AssetRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final AssetRegistryService assetRegistryService;

    /**
     * 创建旧资产映射迁移服务。
     */
    public LegacyAssetMappingMigrationService(AssetRepository repository, TransactionTemplate transactionTemplate,
                                              AssetRegistryService assetRegistryService) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.assetRegistryService = assetRegistryService;
    }

    /**
     * 处理一个有界迁移批次。
     */
    public LegacyAssetMappingResult migrate(LegacyAssetMappingBatch batch) {
        int accepted = 0;
        int skipped = 0;
        int rejected = 0;
        MessageDigest batchDigest = digest();
        Map<String, String> batchIdentities = new HashMap<>();
        for (LegacyAssetMappingItem item : batch.items()) {
            String payloadSha256 = itemDigest(item);
            update(batchDigest, payloadSha256);
            String identity = item.sourceSystem() + "\u0000" + item.legacyAssetId();
            String prior = batchIdentities.putIfAbsent(identity, payloadSha256);
            if (prior != null) {
                if (prior.equals(payloadSha256)) {
                    skipped++;
                } else {
                    rejected++;
                }
                continue;
            }
            Classification classification = classifyOrMigrate(batch, item, payloadSha256);
            if (classification == Classification.ACCEPTED) {
                accepted++;
            } else if (classification == Classification.SKIPPED) {
                skipped++;
            } else {
                rejected++;
            }
        }
        return new LegacyAssetMappingResult(batch.dryRun(), accepted, skipped, rejected,
                HexFormat.of().formatHex(batchDigest.digest()));
    }

    private Classification classifyOrMigrate(LegacyAssetMappingBatch batch, LegacyAssetMappingItem item,
                                              String payloadSha256) {
        AssetRepository.LegacyAssetMappingRecord existing = repository
                .findLegacyMapping(item.sourceSystem(), item.legacyAssetId()).orElse(null);
        if (existing != null) {
            return existing.payloadSha256().equals(payloadSha256)
                    ? Classification.SKIPPED : Classification.REJECTED;
        }
        try {
            Classification result = transactionTemplate.execute(status -> {
                AssetRepository.LegacyAssetMappingRecord concurrent = repository
                        .findLegacyMapping(item.sourceSystem(), item.legacyAssetId()).orElse(null);
                if (concurrent != null) {
                    return concurrent.payloadSha256().equals(payloadSha256)
                            ? Classification.SKIPPED : Classification.REJECTED;
                }
                var asset = assetRegistryService.register(item.asset());
                if (batch.dryRun()) {
                    // dry-run 执行真实登记校验，但回滚全部目标写入。
                    status.setRollbackOnly();
                    return Classification.ACCEPTED;
                }
                repository.insertLegacyMapping(new AssetRepository.LegacyAssetMappingRecord(
                        batch.migrationKey(), batch.sourceSnapshotId(), item.sourceSystem(),
                        item.legacyAssetId(), asset.id(), payloadSha256, Instant.now()));
                return Classification.ACCEPTED;
            });
            return result == null ? Classification.REJECTED : result;
        } catch (AssetInputInvalidException | AssetVersionConflictException
                 | IdempotencyConflictException exception) {
            return Classification.REJECTED;
        } catch (DuplicateKeyException exception) {
            AssetRepository.LegacyAssetMappingRecord concurrent = repository
                    .findLegacyMapping(item.sourceSystem(), item.legacyAssetId()).orElse(null);
            if (concurrent == null) {
                throw exception;
            }
            return concurrent.payloadSha256().equals(payloadSha256)
                    ? Classification.SKIPPED : Classification.REJECTED;
        }
    }

    private String itemDigest(LegacyAssetMappingItem item) {
        RegisterAssetRequest asset = item.asset();
        MessageDigest value = digest();
        update(value, item.sourceSystem(), item.legacyAssetId(), Long.toString(asset.ownerId()),
                asset.idempotencyKey(), asset.sourceType(), asset.sourceBusinessId(),
                asset.contentSha256().toLowerCase(), Long.toString(asset.sizeBytes()), asset.mimeType());
        RegisterAssetRequest.InitialLocation location = asset.location();
        if (location != null) {
            update(value, location.idempotencyKey(), location.providerType(), location.storageUri(),
                    location.providerVersion());
        }
        return HexFormat.of().formatHex(value.digest());
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

    private enum Classification {
        ACCEPTED,
        SKIPPED,
        REJECTED
    }
}
