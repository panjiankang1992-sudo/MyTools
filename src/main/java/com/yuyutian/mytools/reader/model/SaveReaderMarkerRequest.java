package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存阅读标记请求。
 */
@Data
public class SaveReaderMarkerRequest {
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9-]{1,100}")
    private String markerId;

    @NotBlank
    @Pattern(regexp = "BOOKMARK|ANNOTATION")
    private String kind;

    @NotBlank
    @Pattern(regexp = "sha256:[a-f0-9]{64}")
    private String bookId;

    @NotBlank
    @Size(max = 500)
    private String chapterTitle;

    @NotNull
    @Min(0)
    @Max(1_000_000_000L)
    private Long locator;

    @Size(max = 2000)
    private String note;

    @NotNull
    @Min(0)
    private Long createdAt;

    @NotNull
    @Min(0)
    private Long updatedAt;

    private boolean deleted;

    @NotNull
    @Min(0)
    private Long revision;
}
