package com.yuyutian.mytools.messaging.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** MsgService 模板和收件人迁移批次。 */
public record LegacyReferenceDataBatch(
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String migrationKey,
        boolean dryRun,
        @Valid @Size(max = 200) List<LegacyMessageTemplateItem> templates,
        @Valid @Size(max = 500) List<LegacyKnownRecipientItem> recipients) {
    /** 返回不可变模板集合。 */
    @Override public List<LegacyMessageTemplateItem> templates() {
        return templates == null ? List.of() : List.copyOf(templates);
    }

    /** 返回不可变收件人集合。 */
    @Override public List<LegacyKnownRecipientItem> recipients() {
        return recipients == null ? List.of() : List.copyOf(recipients);
    }
}
