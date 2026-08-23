package com.yuyutian.mytools.storage.model;

import java.nio.file.Path;

/**
 * 已验证的受管本地对象。
 *
 * @param path 物理路径，仅在存储服务进程内使用
 * @param size 字节数
 */
public record StorageObject(Path path, long size) {
}
