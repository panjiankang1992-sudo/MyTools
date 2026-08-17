package com.yuyutian.mytools.localfile.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目录扫描后台任务状态。
 */
@Data
public class ScanTask {

    private String taskId;
    private Long directoryId;
    private String status;
    private Integer scannedCount;
    private Integer newCount;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}
