package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
public record RemoteObjectView(
        @NotBlank @Size(max = 2048) @Pattern(regexp = "^(?!/)(?!.*(?:^|/)\\.\\.(?:/|$))[^\\\\]+$") String path,
        @NotBlank @Size(max = 512) @Pattern(regexp = "^[^/\\\\]+$") String name,
        boolean directory,
        @Min(0) long sizeBytes,
        Instant modifiedAt,
        @Pattern(regexp = "^[a-fA-F0-9]{64}$") String contentSha256) {
}
