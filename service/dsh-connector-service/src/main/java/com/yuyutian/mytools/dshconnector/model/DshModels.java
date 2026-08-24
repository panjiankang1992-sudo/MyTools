package com.yuyutian.mytools.dshconnector.model;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.time.Instant;import java.util.*;
/** DSH 会话模型。 */ public final class DshModels {private DshModels(){}
 public record LegacyBinding(@Positive long legacyId,@Positive long ownerId,@NotBlank @Size(max=128)String dshSessionId,@NotBlank @Size(max=64)String workspaceKey,@NotBlank @Size(max=32)String status,long lastSequence,@NotNull Instant createdAt,@NotNull Instant updatedAt){}
 public record MigrationBatch(@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,128}")String migrationKey,boolean dryRun,@NotNull @Size(max=200)List<@Valid LegacyBinding>items){}
 public record MigrationResult(boolean dryRun,int accepted,int skipped,int rejected,String digestSha256){}
 public record BindingView(UUID id,Long legacyId,long ownerId,String dshSessionId,String workspaceKey,String status,long lastSequence,Instant createdAt,Instant updatedAt){}
 public record Reconciliation(int itemCount,String collectionSha256){}
}
