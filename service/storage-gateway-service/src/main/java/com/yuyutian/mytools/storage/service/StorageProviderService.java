package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.CreateProviderRequest;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.ProviderView;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 远端 Provider 注册和轻量对象查询服务。
 */
@Service
public class StorageProviderService {
    private final StorageRepository repository;
    private final ProviderObjectConnectorRegistry connectorRegistry;

    /**
     * 创建 Provider 服务。
     *
     * @param repository 存储仓储
     * @param connectorRegistry Provider 连接器注册表
     */
    public StorageProviderService(StorageRepository repository, ProviderObjectConnectorRegistry connectorRegistry) {
        this.repository = repository;
        this.connectorRegistry = connectorRegistry;
    }

    /**
     * 按名称幂等注册 Provider，密钥仅保存引用。
     *
     * @param request 创建请求
     * @return Provider 安全视图
     */
    public ProviderView create(CreateProviderRequest request) {
        StorageProvider existing = repository.findProviderByName(request.name()).orElse(null);
        if (existing != null) {
            if (!equivalent(existing, request)) {
                throw new IllegalStateException(ErrorCode.PROVIDER_CONFLICT.code());
            }
            return ProviderView.from(existing);
        }
        Instant now = Instant.now();
        validateEndpoint(request);
        StorageProvider provider = new StorageProvider(UUID.randomUUID(), request.name(), request.providerType(),
                request.remoteKey(), normalizeEndpoint(request.endpointUri()), request.secretRef(), request.enabled(),
                now, now);
        try {
            repository.insertProvider(provider);
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException(ErrorCode.PROVIDER_CONFLICT.code(), exception);
        }
        return ProviderView.from(provider);
    }

    /**
     * 列出 Provider 的一个目录。
     *
     * @param providerId Provider 标识
     * @param path 相对路径
     * @return 标准化对象
     */
    public List<RemoteObjectView> list(UUID providerId, String path) {
        StorageProvider provider = repository.findProviderById(providerId)
                .filter(StorageProvider::enabled)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
        return connectorRegistry.list(provider, path);
    }

    private boolean equivalent(StorageProvider provider, CreateProviderRequest request) {
        return provider.providerType().equals(request.providerType())
                && provider.remoteKey().equals(request.remoteKey())
                && java.util.Objects.equals(provider.endpointUri(), normalizeEndpoint(request.endpointUri()))
                && provider.secretRef().equals(request.secretRef())
                && provider.enabled() == request.enabled();
    }

    private void validateEndpoint(CreateProviderRequest request) {
        if ("RCLONE".equals(request.providerType()) && normalizeEndpoint(request.endpointUri()) == null) {
            return;
        }
        if ("WEBDAV".equals(request.providerType())) {
            NativeProviderEndpointValidator.webDav(request.endpointUri());
            return;
        }
        throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code());
    }

    private String normalizeEndpoint(String endpointUri) {
        return endpointUri == null || endpointUri.isBlank() ? null : endpointUri.trim();
    }
}
