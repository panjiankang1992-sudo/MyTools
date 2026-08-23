package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建标准化入站消息分段的内部模型。
 */
public record CreateInboundMessagePart(@Pattern(regexp = "TEXT|ATTACHMENT") String type,
                                       @Size(max = 10_485_760) String text,
                                       @Pattern(regexp = "IMAGE|VIDEO|RECORD|FILE") String attachmentType,
                                       @Size(max = 512) String providerFileId,
                                       @Size(max = 255) String providerAccountKey,
                                       @Size(max = 4096) String sourceUrl,
                                       @Size(max = 1024) String fileName,
                                       @Size(max = 255) String mimeType,
                                       @Positive Long declaredSize) {
}
