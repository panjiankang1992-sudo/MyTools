package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.model.RemoteContent;
import com.yuyutian.mytools.storage.model.ErrorCode;

import java.util.List;

/**
 * 远端 Provider 轻量对象查询连接器。
 */
public interface ProviderObjectConnector {
    /**
     * 返回连接器支持的 Provider 类型。
     *
     * @return Provider 类型
     */
    String providerType();

    /**
     * 列出 Provider 内一个单级目录。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @return 标准化对象列表
     */
    List<RemoteObjectView> list(StorageProvider provider, String path);

    /**
     * 打开 Provider 内一个普通文件。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @param maximumBytes 最大字节数
     * @return 受限内容流
     */
    default RemoteContent openContent(StorageProvider provider, String path, long maximumBytes) {
        throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_UNSUPPORTED.code());
    }
}
