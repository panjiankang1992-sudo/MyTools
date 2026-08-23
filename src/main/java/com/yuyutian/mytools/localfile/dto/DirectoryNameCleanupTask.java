package com.yuyutian.mytools.localfile.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体目录名称净化后台任务状态。
 */
@Data
public class DirectoryNameCleanupTask {

    private String taskId;
    private Long directoryId;
    private Boolean apply;
    private String status;
    private Integer checkedCount;
    private Integer proposedCount;
    private Integer renamedCount;
    private Integer reviewCount;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    private List<DirectoryRenameProposal> proposals;
}
