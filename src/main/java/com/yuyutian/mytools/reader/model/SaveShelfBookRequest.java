package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存书架元数据请求。
 */
@Data
public class SaveShelfBookRequest {
    @NotBlank
    @Pattern(regexp = "sha256:[a-f0-9]{64}")
    private String syncKey;

    @NotBlank
    @Size(max = 1000)
    private String bookId;

    @NotBlank
    @Size(max = 300)
    private String name;

    @NotBlank
    @Size(max = 200)
    private String author;

    @NotBlank
    @Pattern(regexp = "source|remote")
    private String origin;

    @NotBlank
    @Pattern(regexp = "txt|epub|pdf|mobi|azw3|cbz|cbr|unknown")
    private String format;

    @NotBlank
    @Size(max = 4096)
    private String resourceUri;

    @NotBlank
    @Size(max = 4096)
    private String sourceId;

    @Size(max = 4096)
    private String remoteCoverUrl;

    @NotNull
    @Min(0)
    private Long updatedAt;

    private boolean deleted;

    @NotNull
    @Min(0)
    private Long revision;
}
