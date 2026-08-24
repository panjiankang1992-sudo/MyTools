package com.yuyutian.mytools.messaging.model;

/** MsgService 参考数据迁移结果。 */
public record LegacyReferenceDataResult(boolean dryRun, int acceptedTemplates, int skippedTemplates,
                                        int rejectedTemplates, int acceptedRecipients,
                                        int skippedRecipients, int rejectedRecipients,
                                        String digestSha256) {
}
