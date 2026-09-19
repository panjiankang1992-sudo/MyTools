package com.yuyutian.mytools.video.service;

import com.yuyutian.mytools.video.common.ErrorCode;
import com.yuyutian.mytools.video.common.VideoException;
import com.yuyutian.mytools.video.model.VideoModels.ModeSpec;
import com.yuyutian.mytools.video.model.VideoModels.Model;
import com.yuyutian.mytools.video.model.VideoModels.RoleSpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 视频能力目录的验收开关与逐模式契约测试。 */
class VideoCapabilityTest {
 /** 能力目录只暴露这一个已实测的资源标识。 */
 private static final String RESOURCE_ID = VideoCapability.RESOURCE_ID;

 /**
  * 构造只打开指定开关的能力目录，未列出的开关保持关闭。
  *
  * @param enabled 需要置为 true 的配置键
  * @return 使用 MockEnvironment 的能力目录
  */
 private static VideoCapability capability(String... enabled) {
  MockEnvironment env = new MockEnvironment();
  for (String key : enabled) env.setProperty(key, "true");
  return new VideoCapability(env);
 }

 /**
  * 断言调用抛出携带指定错误码的视频异常。
  *
  * @param expected 期望的稳定错误码
  * @param callable 被调用的业务动作
  */
 private static void assertCode(ErrorCode expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
  Throwable thrown = catchThrowable(callable);
  assertThat(thrown).isInstanceOf(VideoException.class);
  assertThat(((VideoException) thrown).code()).isEqualTo(expected);
 }

 /**
  * 按模式名建立索引，便于逐模式断言。
  *
  * @param model 能力目录中的模型
  * @return 模式名到模式契约的映射
  */
 private static Map<String, ModeSpec> byMode(Model model) {
  return model.modes().stream().collect(Collectors.toMap(ModeSpec::mode, Function.identity()));
 }

 /** 未打开任何开关时，所有模式都不可用且必须给出原因码。 */
 @Test
 void allModesAreUnavailableWithoutAnySwitch() {
  Model model = capability().models().getFirst();
  assertThat(model.id()).isEqualTo(RESOURCE_ID);
  assertThat(model.status()).isEqualTo("UNVERIFIED");
  assertThat(model.reasonCode()).isNotBlank();
  assertThat(model.fps()).isEqualTo(16);
  assertThat(model.sizes()).containsExactly("832x480");
  assertThat(model.modes()).isNotEmpty();
  for (ModeSpec mode : model.modes()) {
   assertThat(mode.validated()).as(mode.mode()).isFalse();
   assertThat(mode.reasonCode()).as(mode.mode()).isNotBlank();
  }
  assertCode(ErrorCode.VIDEO_002, () -> capability().require(RESOURCE_ID, "FIRST_FRAME"));
 }

 /** 只开本地验收、不开 GPU 协同验收时，所有模式仍不可用。 */
 @Test
 void localValidationAloneKeepsModesUnavailable() {
  VideoCapability catalog = capability("video.local-validated");
  Model model = catalog.models().getFirst();
  assertThat(model.status()).isEqualTo("UNVERIFIED");
  assertThat(model.reasonCode()).isNotBlank();
  for (ModeSpec mode : model.modes()) {
   assertThat(mode.validated()).as(mode.mode()).isFalse();
   assertThat(mode.reasonCode()).as(mode.mode()).isNotBlank();
  }
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require(RESOURCE_ID, "FIRST_FRAME"));
 }

 /** 模式开关打开但 GPU 协同未验收时也不能提交：目录状态与创建校验必须一致。 */
 @Test
 void modeSwitchWithoutGpuCoordinationStillBlocksSubmit() {
  VideoCapability catalog = capability("video.local-validated", "video.first-frame-validated");
  Model model = catalog.models().getFirst();
  assertThat(model.status()).isEqualTo("UNVERIFIED");
  assertThat(byMode(model).get("FIRST_FRAME").validated()).isFalse();
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require(RESOURCE_ID, "FIRST_FRAME"));
 }

 /** 只开两个引擎级开关、未逐模式验收时，引擎就绪但模式依旧被拒绝。 */
 @Test
 void readyEngineWithoutModeValidationStillRejectsEveryMode() {
  VideoCapability catalog = capability("video.local-validated", "video.gpu-coordination-validated");
  Model model = catalog.models().getFirst();
  assertThat(model.status()).isEqualTo("READY");
  assertThat(model.reasonCode()).isEmpty();
  for (ModeSpec mode : model.modes()) {
   assertThat(mode.validated()).as(mode.mode()).isFalse();
   assertThat(mode.reasonCode()).as(mode.mode()).isNotBlank();
  }
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require(RESOURCE_ID, "TEXT_TO_VIDEO"));
 }

 /** 打开本地、GPU 与首帧三个开关后，只有 FIRST_FRAME 可用。 */
 @Test
 void firstFrameIsTheOnlyUsableModeAfterItsValidation() {
  VideoCapability catalog = capability("video.local-validated", "video.gpu-coordination-validated",
   "video.first-frame-validated");
  Model model = catalog.models().getFirst();
  assertThat(model.status()).isEqualTo("READY");
  assertThat(model.reasonCode()).isEmpty();
  Map<String, ModeSpec> modes = byMode(model);
  assertThat(modes.get("FIRST_FRAME").validated()).isTrue();
  assertThat(modes.get("FIRST_FRAME").reasonCode()).isEmpty();
  for (Map.Entry<String, ModeSpec> entry : modes.entrySet()) {
   if (entry.getKey().equals("FIRST_FRAME")) continue;
   assertThat(entry.getValue().validated()).as(entry.getKey()).isFalse();
   assertThat(entry.getValue().reasonCode()).as(entry.getKey()).isNotBlank();
  }
  assertThat(catalog.require(RESOURCE_ID, "FIRST_FRAME").mode()).isEqualTo("FIRST_FRAME");
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require(RESOURCE_ID, "FIRST_LAST_FRAMES"));
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require(RESOURCE_ID, "SUBJECT_REFERENCES"));
 }

 /** 未知资源、未知模式与未验收模式都以 VIDEO_002 拒绝。 */
 @Test
 void requireRejectsUnknownResourceUnknownModeAndUnvalidatedMode() {
  VideoCapability catalog = capability("video.local-validated", "video.gpu-coordination-validated");
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require("another-resource", "FIRST_FRAME"));
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require(RESOURCE_ID, "NO_SUCH_MODE"));
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require(RESOURCE_ID, "FIRST_FRAME"));
  assertCode(ErrorCode.VIDEO_002, () -> catalog.require(null, null));
 }

 /** SUBJECT_REFERENCES 只接受恰好两张参考图，TEXT_TO_VIDEO 只接受 49 帧。 */
 @Test
 void subjectReferencesTakesExactlyTwoInputsAndTextToVideoOnly49Frames() {
  VideoCapability catalog = capability("video.local-validated", "video.gpu-coordination-validated",
   "video.references-validated", "video.t2v-validated");
  Map<String, ModeSpec> modes = byMode(catalog.models().getFirst());

  ModeSpec references = modes.get("SUBJECT_REFERENCES");
  assertThat(references.validated()).isTrue();
  assertThat(references.minInputs()).isEqualTo(2);
  assertThat(references.maxInputs()).isEqualTo(2);
  assertThat(references.roles()).containsExactly(new RoleSpec("SUBJECT", "IMAGE"));
  assertThat(references.requiresPrompt()).isTrue();

  ModeSpec textToVideo = modes.get("TEXT_TO_VIDEO");
  assertThat(textToVideo.frames()).containsExactly(49);
  assertThat(textToVideo.minInputs()).isZero();
  assertThat(textToVideo.maxInputs()).isZero();
  assertThat(textToVideo.roles()).isEmpty();
  assertThat(catalog.allowsFrames(textToVideo, 49)).isTrue();
  assertThat(catalog.allowsFrames(textToVideo, 48)).isFalse();
  assertThat(catalog.allowsFrames(textToVideo, 81)).isFalse();
 }

 /** 每个模式的输入角色都固定映射到图片或视频，供 App 决定上传入口。 */
 @Test
 void everyModeDeclaresItsRoleToMaterialKindMapping() {
  VideoCapability catalog = capability();
  Map<String, ModeSpec> modes = byMode(catalog.models().getFirst());
  assertThat(modes.get("FIRST_FRAME").roles()).containsExactly(new RoleSpec("FIRST_FRAME", "IMAGE"));
  assertThat(modes.get("FIRST_LAST_FRAMES").roles())
   .containsExactly(new RoleSpec("FIRST_FRAME", "IMAGE"), new RoleSpec("LAST_FRAME", "IMAGE"));
  assertThat(modes.get("STRUCTURE_RESTYLE").roles()).containsExactly(new RoleSpec("SOURCE_VIDEO", "VIDEO"));
  assertThat(modes.get("MASKED_EDIT").roles())
   .containsExactly(new RoleSpec("SOURCE_VIDEO", "VIDEO"), new RoleSpec("MASK_VIDEO", "VIDEO"));
  assertThat(modes.get("MASKED_EDIT").minInputs()).isEqualTo(1);
  assertThat(modes.get("MASKED_EDIT").maxInputs()).isEqualTo(2);
  assertThat(VideoCapability.SIZES).isEqualTo(List.of("832x480"));
 }
}
