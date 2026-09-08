package com.yuyutian.mytools.reader.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 执行器回写章节正文快照的受限批次。
 *
 * @param chapters 正文快照条目
 */
public record AudiobookTextProjectionBatchRequest(
        @NotEmpty @Size(max = 200) List<@Valid Chapter> chapters) {

    /**
     * 一条已发布的章节正文快照。
     *
     * @param index 章节序号
     * @param contentSha256 归一化正文摘要
     * @param storageUri 受管正文快照地址
     * @param sizeBytes UTF-8 正文字节数
     * @param characterCount 归一化正文的 Unicode 码点数
     */
    public record Chapter(@PositiveOrZero int index,
                          @NotBlank @Size(min = 64, max = 64) String contentSha256,
                          @NotBlank @Size(max = 2048) String storageUri,
                          @Positive long sizeBytes,
                          @Positive long characterCount) {

        /**
         * 为仅构造测试快照的旧调用提供保守的字节数上界。
         *
         * @param index 章节序号
         * @param contentSha256 归一化正文摘要
         * @param storageUri 受管正文快照地址
         * @param sizeBytes UTF-8 正文字节数
         */
        public Chapter(int index, String contentSha256, String storageUri, long sizeBytes) {
            this(index, contentSha256, storageUri, sizeBytes, sizeBytes);
        }
    }
}
