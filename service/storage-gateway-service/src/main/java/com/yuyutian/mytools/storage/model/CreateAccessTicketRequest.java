package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建受管对象访问票据请求。
 *
 * @param rootName 受管根名称
 * @param path 根内相对路径
 * @param expiresSeconds 有效秒数
 */
public record CreateAccessTicketRequest(
        @NotBlank @Size(max = 128) String rootName,
        @NotBlank @Size(max = 2048) String path,
        @Min(1) @Max(3600) int expiresSeconds) {
}
