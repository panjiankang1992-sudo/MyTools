package com.yuyutian.mytools.storage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 从冻结清单创建原生树复制子操作的请求。
 *
 * @param sourceObjectPath 来源对象路径
 */
public record CreateNativeTreeChildRequest(
        @NotBlank @Size(max = 2048)
        @Pattern(regexp = "^(?!/)(?!.*(?:^|/)\\.\\.(?:/|$))[^:\\\\]+$") String sourceObjectPath) {
}
