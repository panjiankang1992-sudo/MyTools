package com.yuyutian.mytools.messaging.model;

import java.util.UUID;

/**
 * 标准化入站消息分段。
 */
public record InboundMessagePart(UUID id, int sequence, String type, String text, String attachmentType,
                                 String providerFileId, String sourceUrl, String fileName, String mimeType,
                                 Long declaredSize) {
}
