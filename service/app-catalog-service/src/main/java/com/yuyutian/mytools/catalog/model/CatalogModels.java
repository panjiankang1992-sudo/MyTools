package com.yuyutian.mytools.catalog.model;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.time.Instant;import java.util.List;import java.util.UUID;
/** 应用目录传输模型。 */ public final class CatalogModels {private CatalogModels(){}
 public record LegacyApp(@NotBlank @Size(max=19)String legacyId,@Positive long ownerId,@NotBlank @Size(max=100)String name,@NotBlank @Size(max=20)String appType,@NotBlank @Size(max=50)String currentVersion,@Size(max=19)String legacyThumbnailId,String content,@Size(max=500)String installCommand,@Size(max=500)String downloadUrl,@NotBlank @Size(max=20)String status,@NotNull Instant createdAt,@NotNull Instant updatedAt){}
 public record LegacyVersion(@NotBlank @Size(max=19)String legacyId,@NotBlank @Size(max=50)String version,String content,@Size(max=19)String legacyFileId,@NotNull Instant createdAt){}
 public record LegacyFile(@NotBlank @Size(max=19)String legacyId,@Size(max=19)String legacyVersionId,@NotBlank @Size(max=20)String fileType,@NotBlank @Size(max=255)String fileName,@NotBlank @Size(max=500)String legacyStoragePath,@PositiveOrZero long fileSize,@NotNull Instant createdAt){}
 public record LegacyAppImport(@NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,128}")String migrationKey,boolean dryRun,@NotNull @Valid LegacyApp app,@NotNull @Valid List<LegacyVersion> versions,@NotNull @Valid List<LegacyFile> files){}
 public record ImportResult(boolean dryRun,int accepted,int skipped,int rejected,String digestSha256){}
 public record CatalogView(UUID id,String legacyId,long ownerId,String name,String appType,String currentVersion,String status,int versionCount,int fileCount){}
 public record Reconciliation(int appCount,int versionCount,int fileCount,int unresolvedFileCount,String digestSha256){}
}
