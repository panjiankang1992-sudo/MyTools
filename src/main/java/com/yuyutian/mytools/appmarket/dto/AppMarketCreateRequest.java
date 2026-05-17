package com.yuyutian.mytools.appmarket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 应用上架请求DTO。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
public class AppMarketCreateRequest {

    @NotBlank(message = "应用名称不能为空")
    @Size(max = 100, message = "应用名称最多100字符")
    private String name;

    @NotNull(message = "应用类型不能为空")
    private String type;

    @NotBlank(message = "版本号不能为空")
    @Size(max = 50, message = "版本号最多50字符")
    private String version;

    private String content;

    @Size(max = 500, message = "安装命令最多500字符")
    private String installCmd;

    @Size(max = 500, message = "外部下载链接最多500字符")
    private String downloadUrl;
}
