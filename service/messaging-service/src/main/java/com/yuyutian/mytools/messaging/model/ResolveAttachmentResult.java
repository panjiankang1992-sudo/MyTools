package com.yuyutian.mytools.messaging.model;

import java.util.UUID;

/**
 * 附件渠道引用解析结果。
 */
public record ResolveAttachmentResult(UUID jobId, String status, boolean resolved) {
}
