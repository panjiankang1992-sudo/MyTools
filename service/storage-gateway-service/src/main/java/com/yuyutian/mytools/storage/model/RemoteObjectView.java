package com.yuyutian.mytools.storage.model;

import java.time.Instant;

/**
 * 标准化远端对象元数据。
 *
 * @param path Provider 内路径
 * @param name 对象名称
 * @param directory 是否目录
 * @param sizeBytes 字节数
 * @param modifiedAt 修改时间
 * @param contentSha256 SHA-256 摘要
 */
public record RemoteObjectView(String path, String name, boolean directory, long sizeBytes,
                               Instant modifiedAt, String contentSha256) {
}
