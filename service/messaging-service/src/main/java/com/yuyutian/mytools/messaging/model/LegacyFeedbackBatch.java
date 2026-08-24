package com.yuyutian.mytools.messaging.model;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.List;
/** 旧问题反馈迁移批次。 */ public record LegacyFeedbackBatch(@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,128}")String migrationKey,boolean dryRun,@NotNull @Size(max=200)List<@Valid LegacyFeedbackItem>items){}
