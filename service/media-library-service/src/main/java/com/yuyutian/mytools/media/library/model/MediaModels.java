package com.yuyutian.mytools.media.library.model;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.time.Instant;import java.util.*;
/** Media Library API 模型。 */ public final class MediaModels { private MediaModels(){}
 public record AssetEvent(@NotBlank @Size(max=255)String eventId,@NotNull UUID assetId,@NotNull Long ownerId,@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$")String sourceType,@NotBlank @Size(max=255)String sourceBusinessId,@NotBlank @Size(max=512)String displayName,@NotBlank @Size(max=255)String mimeType,@Positive long sizeBytes,@NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$")String contentSha256,@Size(max=255)String directoryKey,@Size(max=512)String directoryName,UUID scanId){}
 public record MediaView(UUID id,long ownerId,UUID assetId,String displayName,String mimeType,long sizeBytes,String contentSha256,String status,long version,List<String>tags){}
 public record MediaPage(List<MediaView>items,UUID nextAfterId){}
 public record MediaTagCount(String name,long fileCount){}
 public record MediaCatalogPage(List<MediaView>items,long total,int page,int pageSize,List<MediaTagCount>tags){}
 public record EbookPage(List<MediaView>items,long total,int page,int pageSize){}
 public record BeginAnalysis(@NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,64}$")String analysisVersion,@NotNull UUID taskInstanceId,@NotNull UUID assetId){}
 public record AnalysisView(UUID id,UUID mediaItemId,String analysisVersion,UUID taskInstanceId,String status){}
 public record TagInput(@NotBlank @Size(max=128)String name,@DecimalMin("0.0") @DecimalMax("1.0")Double confidence){}
 public record LegacyMediaImport(@NotBlank @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$")String migrationKey,@NotBlank @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$")String sourceSnapshotId,@NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,64}$")String sourceSystem,@NotBlank @Size(max=255)String legacyAssetId,@NotNull @Valid AssetEvent event,@NotNull @Size(max=256)List<@Valid TagInput>tags){}
 public record LegacyMigrationEvidence(String migrationKey,String sourceSnapshotId,int itemCount,int tagCount,String collectionSha256){}
 public record ArtifactInput(@NotNull UUID assetId,@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$")String kind,@NotBlank @Size(max=64)String generatorVersion){}
 public record CompleteAnalysis(@NotNull UUID taskInstanceId,@Size(max=2000)String summary,@Size(max=20000)String description,@Size(max=32)List<@Valid TagInput>tags,@Size(max=64)List<@Valid ArtifactInput>artifacts){}
 public record FailAnalysis(@NotNull UUID taskInstanceId,@NotBlank @Pattern(regexp="^(FAILED|TIMED_OUT|CANCELLED)$")String status,@NotBlank @Size(max=128)String errorCode){}
 public record ProgressRequest(@PositiveOrZero long positionMs,@PositiveOrZero long durationMs,boolean completed,@PositiveOrZero long expectedRevision,@NotNull Instant clientUpdatedAt){}
 public record ProgressView(long ownerId,UUID mediaItemId,long positionMs,long durationMs,boolean completed,long revision,Instant clientUpdatedAt,Instant serverUpdatedAt){}
 public record BeginScan(@NotNull Long ownerId,@NotBlank @Size(max=255)String idempotencyKey,@NotBlank @Size(max=255)String directoryKey,@NotBlank @Size(max=512)String directoryName,@NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$")String rootFingerprint,@PositiveOrZero int expectedCount){}
 public record ScanEntry(@NotBlank @Size(max=255)String sourceBusinessId,@NotBlank @Size(max=512)String displayName,@NotBlank @Size(max=255)String mimeType,@Positive long sizeBytes,@NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$")String contentSha256){}
 public record StageScanEntries(@NotNull @Size(max=1000)List<@Valid ScanEntry>entries){}
 public record FinishScan(@NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$")String manifestSha256){}
 public record ScanView(UUID id,long ownerId,String directoryKey,String status,int expectedCount,int importedCount,String manifestSha256){}
 public record ReconciliationPage(UUID nextAfterId,long libraryRevision,int directoryCount,int completedScanCount,int stagingScanCount,int itemCount,int sourceRelationCount,int sourceTagRelationCount,int readyCount,int missingCount,int analyzingCount,int succeededAnalysisCount,int failedAnalysisCount,int runningAnalysisCount,int tagRelationCount,int artifactCount,int readyDirectoryEntryCount,int missingDirectoryEntryCount,String pageDigestSha256){}
 public record StartDirectoryScan(@NotBlank @Size(max=255)String idempotencyKey,@NotBlank @Size(max=4096)String rootPath,@NotBlank @Size(max=255)String directoryKey,@NotBlank @Size(max=512)String directoryName,boolean analyze,@Pattern(regexp="^[A-Za-z0-9._-]{1,64}$")String analysisVersion){}
 public record StartAnalysis(@NotBlank @Size(max=255)String idempotencyKey,@NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,64}$")String analysisVersion,@Min(1) @Max(12)Integer frameCount,@PositiveOrZero Double seekSeconds){}
 public record OperationView(UUID id,long ownerId,String operationType,UUID taskInstanceId,String status,Instant createdAt,Instant updatedAt){}
 public record LegacyAnalysisTarget(UUID mediaItemId,UUID assetRegistryId,long ownerId,String displayName,String mimeType,long sizeBytes,String contentSha256){}
}
