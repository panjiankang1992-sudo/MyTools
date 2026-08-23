package com.yuyutian.mytools.messaging.model;

import java.util.UUID;

/**
 * 附件下载子任务创建结果。
 */
public record ExecuteAttachmentDownloadResult(UUID jobId, UUID downloadRequestId, String status) {
}
