package com.yuyutian.mytools.messaging.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息附件下载任务视图。
 */
public record AttachmentDownloadView(UUID id, UUID messageId, UUID partId, String status, UUID taskId,
                                     UUID downloadRequestId, String lastErrorCode, Instant createdAt,
                                     Instant updatedAt) {
}
