package com.yuyutian.mytools.cloudfile.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileOperationRequest {

    @NotBlank(message = "path.notBlank")
    private String path;

    /** 用于重命名 */
    private String newName;

    /** 用于移动/复制目标路径 */
    private String to;

    /** 移动/复制来源路径 */
    private String from;

    /** 递归删除目录 */
    private Boolean recursive = false;
}
