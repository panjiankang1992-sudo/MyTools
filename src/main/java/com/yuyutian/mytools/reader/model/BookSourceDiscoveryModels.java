package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 工程化书源导入接口的数据模型。
 */
public final class BookSourceDiscoveryModels {

    private BookSourceDiscoveryModels() {
    }

    public record StartRequest(@NotBlank @Size(max = 4096) String url) {
    }

    public record Task(String taskId, String status, String sourceJson, String message, long updatedAt) {
    }
}
