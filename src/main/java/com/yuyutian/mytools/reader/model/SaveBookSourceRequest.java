package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存书源快照请求。
 */
@Data
public class SaveBookSourceRequest {
    @NotBlank
    @Pattern(regexp = "sha256:[a-f0-9]{64}")
    private String syncKey;

    @NotBlank
    @Size(max = 4096)
    private String sourceUrl;

    @NotBlank
    @Size(max = 524288)
    private String snapshotJson;

    @NotNull
    @Min(0)
    private Long updatedAt;

    private boolean deleted;

    @NotNull
    @Min(0)
    private Long revision;
}
