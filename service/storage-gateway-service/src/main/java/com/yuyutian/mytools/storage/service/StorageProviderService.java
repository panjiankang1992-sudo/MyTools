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
    private final RcloneRemoteConnector connector;

    /**
     * 创建 Provider 服务。
     *
     * @param repository 存储仓储
     * @param connector 受控 rclone 连接器
     */
    public StorageProviderService(StorageRepository repository, RcloneRemoteConnector connector) {
        this.repository = repository;
        this.connector = connector;
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
        StorageProvider provider = new StorageProvider(UUID.randomUUID(), request.name(), request.providerType(),
                request.remoteKey(), request.secretRef(), request.enabled(), now, now);
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
        return connector.list(provider.remoteKey(), path);
    }

    private boolean equivalent(StorageProvider provider, CreateProviderRequest request) {
        return provider.providerType().equals(request.providerType())
                && provider.remoteKey().equals(request.remoteKey())
                && provider.secretRef().equals(request.secretRef())
                && provider.enabled() == request.enabled();
    }
}
