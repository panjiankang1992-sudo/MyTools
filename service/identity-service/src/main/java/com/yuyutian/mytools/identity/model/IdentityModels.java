package com.yuyutian.mytools.identity.model;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** Identity API 数据模型。 */
public final class IdentityModels {
 private IdentityModels() { }
 public record ImportUserRequest(@Positive long id,@NotBlank @Size(max=255) String externalUserId,
   @NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,128}$") String username,@Email @Size(max=320) String email,
   @NotBlank @Size(max=255) String passwordHash,@NotBlank @Pattern(regexp="ACTIVE|DISABLED|LOCKED") String status,
   @PositiveOrZero long credentialVersion,@NotEmpty @Size(max=32) List<@Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String> roles) { }
 public record UserView(long id,String externalUserId,String username,String email,String status,long credentialVersion,List<String> roles) { }
 public record LoginRequest(@NotBlank @Size(max=128) String username,@NotBlank @Size(max=1024) String password,
   @NotBlank @Size(max=255) String deviceId) { }
 public record TokenPair(String accessToken,String refreshToken,String tokenType,long expiresIn,long refreshExpiresIn,
   UUID sessionId,long userId,String username,List<String> roles) { }
 public record RefreshRequest(@NotBlank @Size(max=1024) String refreshToken) { }
 public record ValidateRequest(@NotBlank @Size(max=4096) String accessToken) { }
 public record PrincipalView(boolean active,long userId,String username,List<String> roles,UUID sessionId,Instant expiresAt) { }
 public record SessionRecord(UUID id,long userId,String deviceId,String refreshHash,long version,long credentialVersion,
   Instant issuedAt,Instant refreshExpiresAt,Instant revokedAt) { }
 public record SessionView(UUID id,String deviceId,String status,Instant issuedAt,Instant refreshExpiresAt,
   Instant lastSeenAt) { }
 public record LegacyUserMigrationBatch(
   @NotBlank @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$") String migrationKey,
   boolean dryRun,
   @NotEmpty @Size(max=100) List<@Valid ImportUserRequest> users) { }
 public record LegacyUserMigrationResult(String migrationKey,boolean dryRun,int exported,int accepted,
   int skipped,int rejected,String digestSha256) { }
 public record LegacyUserReconciliation(String migrationKey,int itemCount,String collectionSha256) { }
}
