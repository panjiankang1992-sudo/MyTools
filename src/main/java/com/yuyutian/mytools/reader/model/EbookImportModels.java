package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 电子书上传与书源导入接口模型。
 */
public final class EbookImportModels {
    private EbookImportModels() {
    }

    /**
     * 书源图书后台导入请求。
     *
     * @param sourceUrl 书源地址
     * @param bookUrl 图书地址
     * @param title 图书名称
     * @param author 作者
     */
    public record SourceRequest(@NotBlank @Size(max = 4096) String sourceUrl,
                                @NotBlank @Size(max = 4096) String bookUrl,
                                @NotBlank @Size(max = 300) String title,
                                @Size(max = 200) String author) {
    }

    /**
     * 后台导入任务快照。
     *
     * @param taskId 任务标识
     * @param status 任务状态
     * @param fileName 目标文件名
     * @param message 状态说明
     * @param updatedAt 更新时间
     */
    public record Task(String taskId, String status, String fileName, String message, long updatedAt) {
    }

    /**
     * 本地文件上传结果。
     *
     * @param fileName 保存后的文件名
     * @param size 文件大小
     */
    public record UploadResult(String fileName, long size) {
    }
}
