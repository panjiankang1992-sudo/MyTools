package com.yuyutian.mytools.image.model;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;
/** 图片服务公开数据，不包含端点、凭据或工作流节点。 */
public final class ImageModels {
 private ImageModels() { }
 /** 模型能力与可用状态。 */
 public record Model(String id, String name, String location, String status, List<String> modes,
                     List<String> sizes, List<Integer> counts, int maxReferences, String reasonCode) { }
 /** 生成请求。 */
 public record Create(@NotBlank @Size(max=100) String resourceId,
     @NotBlank @Size(max=4000) String prompt, @NotBlank String mode,
     @NotNull @Size(max=2) List<UUID> references, @NotBlank String size,
     @Min(1) @Max(4) int count, @Min(0) @Max(2147483647) long seed,
     @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String idempotencyKey,
     @Size(max=64) String styleId, @Min(1) Integer styleVersion, UUID styleSourceJobId) {
  /** 保持旧客户端和已有调用方兼容。 */
  public Create(String resourceId,String prompt,String mode,List<UUID> references,String size,int count,long seed,String idempotencyKey) {
   this(resourceId,prompt,mode,references,size,count,seed,idempotencyKey,null,null,null);
  }
 }
 /** 私有文字风格编辑，版本用于检测并发覆盖。 */
 public record StyleWrite(@NotBlank @Size(max=80) String name,
     @NotBlank @Size(max=2000) String promptTemplate, @Min(1) Integer expectedVersion) { }
 /** 有界图片上传，上传内容在服务端再次检测。 */
 public record Upload(@NotBlank @Size(max=7000000) String base64) { }
 /** 上传结果。 */
 public record Uploaded(UUID id, String mimeType, long sizeBytes) { }
}
