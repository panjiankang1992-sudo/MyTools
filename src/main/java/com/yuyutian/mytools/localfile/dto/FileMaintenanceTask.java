package com.yuyutian.mytools.localfile.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件维护后台任务状态。
 */
@Data
public class FileMaintenanceTask {

    private String taskId;
    private Long directoryId;
    private String mode;
    private String status;
    private Integer checkedCount;
    private Integer duplicateCount;
    private Integer renamedCount;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}
