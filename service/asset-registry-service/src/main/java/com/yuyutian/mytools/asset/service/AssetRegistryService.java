package com.yuyutian.mytools.asset.service;

import com.yuyutian.mytools.asset.model.AssetView;
import com.yuyutian.mytools.asset.model.RegisterArtifactRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.model.RegisterLocationRequest;
import com.yuyutian.mytools.asset.repository.AssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
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
}
