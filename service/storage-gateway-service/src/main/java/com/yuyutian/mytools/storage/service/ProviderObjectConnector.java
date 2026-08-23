package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.model.RemoteContent;
import com.yuyutian.mytools.storage.model.ErrorCode;

import java.util.List;
import java.io.InputStream;

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
     * 返回是否支持普通文件内容读取。
     *
     * @return 是否支持读取
     */
    default boolean supportsContentRead() {
        return false;
    }

    /**
     * 返回是否支持普通文件内容写入和补偿删除。
     *
     * @return 是否支持写入
     */
    default boolean supportsContentWrite() {
        return false;
    }

    /**
     * 返回连接器单次普通文件写入上限。
     *
     * @return 最大字节数，不支持写入时为零
     */
    default long maximumContentWriteBytes() {
        return 0;
    }

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

    /**
     * 流式写入一个普通文件。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @param content 内容流
     * @param contentLength 精确内容长度
     * @return 是否由本次请求创建目标
     */
    default boolean writeContent(StorageProvider provider, String path, InputStream content, long contentLength) {
        throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_UNSUPPORTED.code());
    }

    /**
     * 删除一个普通文件，供失败补偿使用。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     */
    default void deleteContent(StorageProvider provider, String path) {
        throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_UNSUPPORTED.code());
    }
}
