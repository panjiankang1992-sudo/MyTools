package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.model.RemoteContent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按 Provider 类型选择轻量查询连接器。
 */
@Component
public class ProviderObjectConnectorRegistry {
    private final Map<String, ProviderObjectConnector> connectors;

    /**
     * 创建连接器注册表。
     *
     * @param connectors Spring 提供的连接器集合
     */
    public ProviderObjectConnectorRegistry(List<ProviderObjectConnector> connectors) {
        this.connectors = connectors.stream().collect(Collectors.toUnmodifiableMap(
                ProviderObjectConnector::providerType, Function.identity()));
    }

    /**
     * 使用 Provider 类型对应的连接器列目录。
     *
     * @param provider Provider 配置
     * @param path 相对路径
     * @return 标准化对象列表
     */
    public List<RemoteObjectView> list(StorageProvider provider, String path) {
        ProviderObjectConnector connector = connectors.get(provider.providerType());
        if (connector == null) {
            throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code());
        }
        return connector.list(provider, path);
    }

    /** 打开远端对象。 @param provider Provider @param path 路径 @param maximumBytes 上限 @return 内容 */
    public RemoteContent openContent(StorageProvider provider, String path, long maximumBytes) {
        ProviderObjectConnector connector = connectors.get(provider.providerType());
        if (connector == null) {
            throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code());
        }
        return connector.openContent(provider, path, maximumBytes);
    }
}
