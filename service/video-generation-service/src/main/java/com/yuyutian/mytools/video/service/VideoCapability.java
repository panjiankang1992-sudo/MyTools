package com.yuyutian.mytools.video.service;
import com.yuyutian.mytools.video.common.ErrorCode;
import com.yuyutian.mytools.video.common.VideoException;
import com.yuyutian.mytools.video.model.VideoModels.ModeSpec;
import com.yuyutian.mytools.video.model.VideoModels.Model;
import com.yuyutian.mytools.video.model.VideoModels.RoleSpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
/** 视频能力目录：只有显式通过验收的模式才允许提交。 */
@Component
public class VideoCapability {
 /** 本机 VACE 1.3B 基线的资源标识。 */
 public static final String RESOURCE_ID = "wan-vace-1.3b-local";
 /** 已实测通过的横屏尺寸；竖屏尚未验证，因此不列出。 */
 public static final List<String> SIZES = List.of("832x480");
 /** 角色到素材类型的固定映射，App 据此决定上传图片还是视频。 */
 private static final RoleSpec SUBJECT = new RoleSpec("SUBJECT", "IMAGE");
 private static final RoleSpec FIRST_FRAME = new RoleSpec("FIRST_FRAME", "IMAGE");
 private static final RoleSpec LAST_FRAME = new RoleSpec("LAST_FRAME", "IMAGE");
 private static final RoleSpec SOURCE_VIDEO = new RoleSpec("SOURCE_VIDEO", "VIDEO");
 private static final RoleSpec MASK_VIDEO = new RoleSpec("MASK_VIDEO", "VIDEO");
 private final Environment env;
 /** 注入验收开关。 */
 public VideoCapability(Environment env) { this.env = env; }
 /** 返回能力目录；未通过验收的模式保留在列表中但标记为不可用并给出原因。 */
 public List<Model> models() {
  boolean ready = engine();
  List<ModeSpec> modes = List.of(
   mode("TEXT_TO_VIDEO", 0, 0, List.of(), true, List.of(49), "video.t2v-validated",
        "INSTRUCTION_FOLLOWING_UNVERIFIED"),
   // P0 结论：只有"单图首帧 + 控制强度 α=0.25"通过 3 主体 × 2 种子网格与机器复核。
   mode("FIRST_FRAME", 1, 1, List.of(FIRST_FRAME), true, List.of(49), "video.first-frame-validated",
        "MODE_VALIDATION_REQUIRED"),
   // 只放行两张参考：三图参考在 P0 判为不可用，收下第三张只会产出未验收的结果。
   mode("SUBJECT_REFERENCES", 2, 2, List.of(SUBJECT), true, List.of(49), "video.references-validated",
        "REFERENCE_CONTROL_UNVERIFIED"),
   mode("FIRST_LAST_FRAMES", 2, 2, List.of(FIRST_FRAME, LAST_FRAME), true, List.of(49), "video.first-last-validated",
        "MODE_VALIDATION_REQUIRED"),
   mode("STRUCTURE_RESTYLE", 1, 1, List.of(SOURCE_VIDEO), true, List.of(49), "video.restyle-validated",
        "SOURCE_QUALITY_UNVERIFIED"),
   mode("MASKED_EDIT", 1, 2, List.of(SOURCE_VIDEO, MASK_VIDEO), true, List.of(49), "video.masked-validated",
        "LOCALITY_DISPUTED"));
  return List.of(new Model(RESOURCE_ID, "Wan 2.1 VACE 1.3B", "LOCAL", ready ? "READY" : "UNVERIFIED",
   SIZES, List.of(49), 16, modes, ready ? "" : "LOCAL_VALIDATION_REQUIRED"));
 }
 /** 按资源标识与模式取契约；未知资源或模式一律拒绝。 */
 public ModeSpec require(String resourceId, String mode) {
  if (!RESOURCE_ID.equals(resourceId)) throw new VideoException(ErrorCode.VIDEO_002);
  Optional<ModeSpec> spec = models().getFirst().modes().stream()
   .filter(item -> item.mode().equals(mode)).findFirst();
  ModeSpec found = spec.orElseThrow(() -> new VideoException(ErrorCode.VIDEO_002));
  if (!found.validated()) throw new VideoException(ErrorCode.VIDEO_002);
  return found;
 }
 /** 该模式是否允许这一帧数。 */
 public boolean allowsFrames(ModeSpec spec, int frames) { return spec.frames().contains(frames); }
 private ModeSpec mode(String mode, int minInputs, int maxInputs, List<RoleSpec> roles, boolean requiresPrompt,
                       List<Integer> frames, String key, String reason) {
  // 引擎级门禁（本机模型验收 + 显存协调）不通过时任何模式都不得放行；
  // 目录状态与提交校验必须用同一个判断，否则会出现"目录说不可用、创建却成功"。
  boolean validated = engine() && flag(key);
  return new ModeSpec(mode, minInputs, maxInputs, roles, requiresPrompt, frames, validated,
   validated ? "" : reason);
 }
 private boolean engine() { return flag("video.local-validated") && flag("video.gpu-coordination-validated"); }
 private boolean flag(String key) { return env.getProperty(key, Boolean.class, false); }
}
