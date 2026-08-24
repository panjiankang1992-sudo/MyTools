package com.yuyutian.mytools.gateway.model;

import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.time.Instant;import java.util.*;
/** Reader 搜索 Gateway 模型。 */ public final class ReaderSearchGatewayModels {private ReaderSearchGatewayModels(){}
 public record SourceSnapshot(@NotBlank @Size(max=255)String id,@NotBlank @Size(max=300)String name,@NotBlank @Size(max=2000)String url,@NotNull Integer revision,@NotNull Map<String,Object>snapshot){}
 public record CreateSearch(@NotBlank @Size(max=255)String idempotencyKey,@NotBlank @Size(max=200)String keyword,@NotBlank @Pattern(regexp="^(FUZZY|EXACT)$")String mode,@Min(1)@Max(1000)int page,@NotEmpty @Size(max=500)List<@Valid SourceSnapshot>sources){}
 public record SearchView(UUID id,String status,String keyword,String mode,int page,int completedShards,int failedShards,int totalShards,List<Map<String,Object>>results,Instant createdAt,Instant updatedAt){}
}
