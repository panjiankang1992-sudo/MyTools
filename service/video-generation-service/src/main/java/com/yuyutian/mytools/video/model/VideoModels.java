package com.yuyutian.mytools.video.model;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
/** 视频服务公开数据，不包含端点、凭据、工作流节点或 Comfy 路径。 */
public final class VideoModels {
 private VideoModels() { }
 /** 输入角色及其素材类型，供 App 决定上传图片还是视频。 */
 public record RoleSpec(String name, String kind) { }
 /** 单个模式的输入契约与验收状态；帧数只列出该模式真正验证过的档位。 */
 public record ModeSpec(String mode, int minInputs, int maxInputs, List<RoleSpec> roles,
                        boolean requiresPrompt, List<Integer> frames,
                        boolean validated, String reasonCode) { }
 /** 模型能力与可用状态。 */
 public record Model(String id, String name, String location, String status,
                     List<String> sizes, List<Integer> frames, int fps,
                     List<ModeSpec> modes, String reasonCode) { }
 /** 一份输入素材：只引用本人上传，角色由模式决定。 */
 public record Input(@NotNull UUID uploadId, @NotBlank String role,
                     @Size(max=200) String description,
                     @Min(0) Long trimStartMs, @Min(0) Long trimEndMs) { }
 /** 输出规格：尺寸、帧数与帧率。 */
 public record Output(@NotBlank String size, @Min(1) int frames, @Min(1) int fps) { }
 /** 生成请求；ownerId 由服务端从会话推导，不接受客户端提交。 */
 public record Create(@NotBlank @Size(max=100) String resourceId,
                      @NotBlank @Size(max=4000) String prompt,
                      @NotBlank String mode,
                      @NotNull @Size(max=3) List<@Valid Input> inputs,
                      @NotNull @Valid Output output,
                      @Min(0) @Max(2147483647) long seed,
                      @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String idempotencyKey,
                      @Size(max=16) String audioPolicy,
                      UUID parentJobId) { }
 /** 上传结果：返回服务端实测的类型与元数据。 */
 public record Uploaded(UUID id, String kind, String mimeType, long sizeBytes,
                        Long durationMs, Integer width, Integer height) { }
}
