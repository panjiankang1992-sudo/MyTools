package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建本地对象校验和任务请求。
 *
 * @param idempotencyKey 幂等键
 * @param rootName 受管根名称
 * @param path 根内相对路径
 */
public record CreateChecksumOperationRequest(
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotBlank @Size(max = 128) String rootName,
        @NotBlank @Size(max = 2048) String path) {
}
