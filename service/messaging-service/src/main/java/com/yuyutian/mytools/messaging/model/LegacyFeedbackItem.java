package com.yuyutian.mytools.messaging.model;
import jakarta.validation.constraints.*;import java.time.Instant;
/** 旧问题反馈。 */ public record LegacyFeedbackItem(@Positive long legacyId,@NotBlank @Size(max=50)String username,@Email @NotBlank @Size(max=100)String email,@Size(max=20)String phone,@NotBlank @Size(max=50)String category,@NotBlank @Size(max=200)String title,@NotBlank String content,@NotBlank @Size(max=20)String status,@NotNull Instant createdAt,@NotNull Instant updatedAt){}
