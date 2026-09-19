package com.yuyutian.mytools.video.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.video.common.ErrorCode;
import com.yuyutian.mytools.video.common.VideoException;
import com.yuyutian.mytools.video.model.VideoModels.Create;
import com.yuyutian.mytools.video.model.VideoModels.Input;
import com.yuyutian.mytools.video.model.VideoModels.Output;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 视频任务的素材校验、幂等、裁剪、快照与所有权隔离测试。 */
class VideoServiceTest {
 /** 终态之外的中间状态，耗时随时间增长。 */
 private static final Set<String> OPEN_STATUS = Set.of("QUEUED", "RUNNING", "PERSISTING", "CANCEL_REQUESTED");
 /** 迁移脚本相对模块根目录的固定位置。 */
 private static final String MIGRATION = "db/migrations/V1__create_video_jobs.sql";

 @TempDir Path root;
 JdbcTemplate jdbc;
 ObjectMapper json = new ObjectMapper();
 VideoStore store;
 VideoCapability capability;
 VideoUpstream upstream;
 VideoProbe probe;
 MockEnvironment env;
 VideoService service;

 /**
  * 用真实 H2 schema 与真实参数化 SQL 承接存储层，其余协作者使用 Mockito。
  *
  * @throws Exception 迁移脚本执行或目录创建失败
  */
 @BeforeEach
 void setup() throws Exception {
  DriverManagerDataSource dataSource = new DriverManagerDataSource(
   "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
  new ResourceDatabasePopulator(new FileSystemResource(MIGRATION)).execute(dataSource);
  jdbc = new JdbcTemplate(dataSource);

  store = mock(VideoStore.class);
  when(store.jdbc()).thenReturn(jdbc);
  when(store.json()).thenReturn(json);
  when(store.encode(any())).thenAnswer(invocation -> json.writeValueAsString(invocation.getArgument(0)));
  when(store.job(anyLong(), anyString())).thenAnswer(invocation -> jobRow(
   invocation.getArgument(0, Long.class), invocation.getArgument(1, String.class)));
  when(store.byKey(anyLong(), anyString())).thenAnswer(invocation -> jobRows(
   "SELECT * FROM video_job WHERE owner_id=? AND idempotency_key=?",
   invocation.getArgument(0, Long.class), invocation.getArgument(1, String.class)));
  when(store.list(anyLong(), anyInt())).thenAnswer(invocation -> jobRows(
   "SELECT * FROM video_job WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?",
   invocation.getArgument(0, Long.class), invocation.getArgument(1, Integer.class) * 20));
  when(store.pending()).thenReturn(List.of());

  upstream = mock(VideoUpstream.class);
  probe = mock(VideoProbe.class);

  env = new MockEnvironment()
   .withProperty("video.root", root.toString())
   .withProperty("video.local-validated", "true")
   .withProperty("video.gpu-coordination-validated", "true")
   .withProperty("video.first-frame-validated", "true")
   .withProperty("video.references-validated", "true")
   .withProperty("video.first-last-validated", "true")
   .withProperty("video.restyle-validated", "true")
   .withProperty("video.masked-validated", "true");
  // TEXT_TO_VIDEO 故意保持未验收，用于验证能力门禁。
  VideoCapability catalog = new VideoCapability(env);
  capability = mock(VideoCapability.class);
  when(capability.models()).thenAnswer(invocation -> catalog.models());
  when(capability.require(anyString(), anyString())).thenAnswer(invocation ->
   catalog.require(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class)));
  when(capability.allowsFrames(any(), anyInt())).thenAnswer(invocation ->
   catalog.allowsFrames(invocation.getArgument(0), invocation.getArgument(1, Integer.class)));

  service = new VideoService(store, capability, upstream, probe, env);
 }

 /** 迁移脚本必须能被 H2 以 MySQL 兼容模式完整执行并建出全部表。 */
 @Test
 void migrationRunsOnH2InMysqlCompatibilityMode() {
  for (String table : List.of("video_upload", "video_job", "video_input", "video_output", "video_job_event")) {
   Integer count = jdbc.queryForObject(
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name=?", Integer.class, table);
   assertThat(count).as(table).isEqualTo(1);
  }
  Integer columns = jdbc.queryForObject(
   "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='video_job'", Integer.class);
  assertThat(columns).isGreaterThanOrEqualTo(22);
  assertThat(jdbc.queryForList("SELECT * FROM video_job")).isEmpty();
 }

 /** JPEG 与 PNG 都能通过实测校验并登记服务端认定的类型。 */
 @Test
 void imageUploadPersistsRealMimeTypeAndMetadata() throws Exception {
  var png = service.upload(7, "IMAGE", pngBytes());
  assertThat(png.kind()).isEqualTo("IMAGE");
  assertThat(png.mimeType()).isEqualTo("image/png");
  assertThat(png.durationMs()).isNull();
  assertThat(Files.isRegularFile(root.resolve("uploads").resolve(png.id().toString()))).isTrue();
  Map<String, Object> pngRow = jdbc.queryForMap("SELECT * FROM video_upload WHERE id=?", png.id().toString());
  assertThat(((Number) pngRow.get("owner_id")).longValue()).isEqualTo(7L);
  assertThat(pngRow.get("mime_type")).isEqualTo("image/png");
  assertThat(((Number) pngRow.get("size_bytes")).longValue()).isEqualTo(png.sizeBytes());
  assertThat(pngRow.get("content_sha256").toString()).hasSize(64);

  var jpeg = service.upload(7, "IMAGE", jpegBytes());
  assertThat(jpeg.kind()).isEqualTo("IMAGE");
  assertThat(jpeg.mimeType()).isEqualTo("image/jpeg");
  assertThat(Files.isRegularFile(root.resolve("uploads").resolve(jpeg.id().toString()))).isTrue();
  assertThat(jdbc.queryForObject(
   "SELECT mime_type FROM video_upload WHERE id=?", String.class, jpeg.id().toString())).isEqualTo("image/jpeg");
 }

 /** 空字节、非图片字节与超过 20MB 的图片都以 VIDEO_001 拒绝。 */
 @Test
 void invalidImageUploadsAreRejected() {
  assertCode(ErrorCode.VIDEO_001, () -> service.upload(1, "IMAGE", new byte[0]));
  assertCode(ErrorCode.VIDEO_001, () -> service.upload(1, "IMAGE", "not-an-image".getBytes(StandardCharsets.UTF_8)));
  assertCode(ErrorCode.VIDEO_001, () -> service.upload(1, "IMAGE", new byte[20 * 1024 * 1024 + 1]));
  assertCode(ErrorCode.VIDEO_001, () -> service.upload(1, "AUDIO", new byte[] {1, 2, 3}));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_upload", Integer.class)).isZero();
 }

 /** 视频探测时长非法时拒绝上传，并删除已经落盘的临时文件。 */
 @Test
 void videoUploadRejectsInvalidProbedDurationAndRemovesPersistedFile() throws Exception {
  when(probe.probe(any())).thenReturn(new VideoProbe.Probe(0L, 832, 480, "h264", false));
  assertCode(ErrorCode.VIDEO_001, () -> service.upload(1, "VIDEO", new byte[] {1, 2, 3, 4}));
  assertThat(uploadFiles()).isEmpty();

  when(probe.probe(any())).thenReturn(new VideoProbe.Probe(60_001L, 832, 480, "h264", false));
  assertCode(ErrorCode.VIDEO_001, () -> service.upload(1, "VIDEO", new byte[] {5, 6, 7, 8}));
  assertThat(uploadFiles()).isEmpty();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_upload", Integer.class)).isZero();
 }

 /** 合法的视频探测结果按实测元数据登记。 */
 @Test
 void videoUploadRegistersMeasuredMetadata() throws Exception {
  when(probe.probe(any())).thenReturn(new VideoProbe.Probe(12_000L, 1280, 720, "h264", true));
  var uploaded = service.upload(3, "VIDEO", new byte[] {9, 9, 9});
  assertThat(uploaded.kind()).isEqualTo("VIDEO");
  assertThat(uploaded.mimeType()).isEqualTo("video/mp4");
  assertThat(uploaded.durationMs()).isEqualTo(12_000L);
  assertThat(uploaded.width()).isEqualTo(1280);
  assertThat(uploaded.height()).isEqualTo(720);
  Map<String, Object> row = jdbc.queryForMap("SELECT * FROM video_upload WHERE id=?", uploaded.id().toString());
  assertThat(((Number) row.get("duration_ms")).longValue()).isEqualTo(12_000L);
  assertThat(((Number) row.get("width")).intValue()).isEqualTo(1280);
 }

 /** 未验收模式先于参数校验被拒绝，且不产生任务行。 */
 @Test
 void createRejectsUnvalidatedModeWithCapabilityError() {
  assertCode(ErrorCode.VIDEO_002, () -> service.create(1, request("TEXT_TO_VIDEO", List.of(), "key_gate_001")));
  assertCode(ErrorCode.VIDEO_002, () -> service.create(1, request("NO_SUCH_MODE", List.of(), "key_gate_002")));
  assertCode(ErrorCode.VIDEO_002, () -> service.create(1,
   new Create("another-resource", "prompt", "FIRST_FRAME", List.of(), new Output("832x480", 49, 16),
    42L, "key_gate_003", null, null)));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_job", Integer.class)).isZero();
 }

 /** 尺寸、帧率与帧数不在已验收集合内都以 VIDEO_002 拒绝。 */
 @Test
 void createRejectsOutputOutsideValidatedShape() throws Exception {
  UUID upload = imageUpload(1);
  assertCode(ErrorCode.VIDEO_002, () -> service.create(1, new Create(VideoCapability.RESOURCE_ID, "prompt",
   "FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), new Output("1280x720", 49, 16), 42L,
   "key_shape_001", null, null)));
  assertCode(ErrorCode.VIDEO_002, () -> service.create(1, new Create(VideoCapability.RESOURCE_ID, "prompt",
   "FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), new Output("832x480", 49, 8), 42L,
   "key_shape_002", null, null)));
  assertCode(ErrorCode.VIDEO_002, () -> service.create(1, new Create(VideoCapability.RESOURCE_ID, "prompt",
   "FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), new Output("832x480", 48, 16), 42L,
   "key_shape_003", null, null)));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_job", Integer.class)).isZero();
 }

 /** 同一幂等键且请求体相同直接重放，请求体不同以 VIDEO_008 拒绝。 */
 @Test
 void createReplaysIdenticalRequestAndRejectsChangedBody() throws Exception {
  UUID upload = imageUpload(1);
  Create request = request("FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), "key_idem_0001");
  String firstId = service.create(1, request).path("id").asText();
  String replayId = service.create(1, request).path("id").asText();
  assertThat(replayId).isEqualTo(firstId);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_job WHERE owner_id=1", Integer.class)).isEqualTo(1);

  Create changed = new Create(VideoCapability.RESOURCE_ID, "another prompt", "FIRST_FRAME",
   List.of(input(upload, "FIRST_FRAME")), new Output("832x480", 49, 16), 42L, "key_idem_0001", null, null);
  assertCode(ErrorCode.VIDEO_008, () -> service.create(1, changed));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_job WHERE owner_id=1", Integer.class)).isEqualTo(1);
 }

 /** 素材必须属于请求者本人，否则以 VIDEO_009 拒绝。 */
 @Test
 void createRejectsMaterialOwnedByAnotherAccount() {
  UUID foreign = seedUpload(2, "IMAGE", "image/png", 128L, null, null, null);
  assertCode(ErrorCode.VIDEO_009, () -> service.create(1, request("FIRST_FRAME",
   List.of(input(foreign, "FIRST_FRAME")), "key_owner_001")));
  assertCode(ErrorCode.VIDEO_009, () -> service.create(1, request("SUBJECT_REFERENCES",
   List.of(input(UUID.randomUUID(), "SUBJECT"), input(UUID.randomUUID(), "SUBJECT")), "key_owner_002")));
 }

 /** 角色声明的素材类型必须与服务端实测类型一致。 */
 @Test
 void createRejectsRoleKindMismatch() {
  UUID video = seedUpload(1, "VIDEO", "video/mp4", 2048L, 6_000L, 832, 480);
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("SUBJECT_REFERENCES",
   List.of(input(video, "SUBJECT"), input(seedUpload(1, "VIDEO", "video/mp4", 2048L, 6_000L, 832, 480), "SUBJECT")),
   "key_kind_0001")));
 }

 /** 同一素材不能被同一任务重复引用。 */
 @Test
 void createRejectsRepeatedUploadId() {
  UUID upload = seedUpload(1, "IMAGE", "image/png", 128L, null, null, null);
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("SUBJECT_REFERENCES",
   List.of(input(upload, "SUBJECT"), input(upload, "SUBJECT")), "key_dup_0001")));
 }

 /** 裁剪只适用于视频素材，窗口不得超过 5 秒且必须落在素材时长内。 */
 @Test
 void createValidatesTrimWindow() throws Exception {
  UUID image = imageUpload(1);
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("FIRST_FRAME",
   List.of(trimmed(image, "FIRST_FRAME", 0L, 500L)), "key_trim_001")));

  UUID video = seedUpload(1, "VIDEO", "video/mp4", 2048L, 60_000L, 832, 480);
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("STRUCTURE_RESTYLE",
   List.of(trimmed(video, "SOURCE_VIDEO", 0L, 6_000L)), "key_trim_002")));
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("STRUCTURE_RESTYLE",
   List.of(trimmed(video, "SOURCE_VIDEO", 0L, 60_001L)), "key_trim_003")));
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("STRUCTURE_RESTYLE",
   List.of(trimmed(video, "SOURCE_VIDEO", -1L, 1_000L)), "key_trim_004")));

  var job = service.create(1, request("STRUCTURE_RESTYLE",
   List.of(trimmed(video, "SOURCE_VIDEO", 1_000L, 4_000L)), "key_trim_005"));
  JsonNode resolved = job.path("request").path("resolvedInputs").get(0);
  assertThat(resolved.path("trimStartMs").asLong()).isEqualTo(1_000L);
  assertThat(resolved.path("trimEndMs").asLong()).isEqualTo(4_000L);
  Map<String, Object> inputRow = jdbc.queryForMap("SELECT * FROM video_input WHERE job_id=?",
   job.path("id").asText());
  assertThat(((Number) inputRow.get("trim_start_ms")).longValue()).isEqualTo(1_000L);
  assertThat(((Number) inputRow.get("trim_end_ms")).longValue()).isEqualTo(4_000L);
 }

 /** FIRST_LAST_FRAMES 的首尾帧顺序具有语义，必须严格匹配契约。 */
 @Test
 void createEnforcesFirstLastFrameOrder() throws Exception {
  UUID first = imageUpload(1);
  UUID last = imageUpload(1);
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("FIRST_LAST_FRAMES",
   List.of(input(last, "LAST_FRAME"), input(first, "FIRST_FRAME")), "key_order_001")));
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("FIRST_LAST_FRAMES",
   List.of(input(first, "FIRST_FRAME"), input(last, "FIRST_FRAME")), "key_order_002")));

  var job = service.create(1, request("FIRST_LAST_FRAMES",
   List.of(input(first, "FIRST_FRAME"), input(last, "LAST_FRAME")), "key_order_003"));
  assertThat(job.path("status").asText()).isEqualTo("QUEUED");
  assertThat(job.path("request").path("resolvedInputs")).hasSize(2);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_input WHERE job_id=?", Integer.class,
   job.path("id").asText())).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT ordinal FROM video_input WHERE job_id=? AND role='FIRST_FRAME'",
   Integer.class, job.path("id").asText())).isZero();
  assertThat(jdbc.queryForObject("SELECT ordinal FROM video_input WHERE job_id=? AND role='LAST_FRAME'",
   Integer.class, job.path("id").asText())).isEqualTo(1);
 }

 /** MASKED_EDIT 第一个输入必须是源视频，第二个（若有）必须是遮罩视频。 */
 @Test
 void createEnforcesMaskedEditOrder() {
  UUID source = seedUpload(1, "VIDEO", "video/mp4", 2048L, 6_000L, 832, 480);
  UUID mask = seedUpload(1, "VIDEO", "video/mp4", 2048L, 6_000L, 832, 480);
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("MASKED_EDIT",
   List.of(input(mask, "MASK_VIDEO")), "key_mask_001")));
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, request("MASKED_EDIT",
   List.of(input(source, "SOURCE_VIDEO"), input(source, "SOURCE_VIDEO")), "key_mask_002")));

  var job = service.create(1, request("MASKED_EDIT",
   List.of(input(source, "SOURCE_VIDEO"), input(mask, "MASK_VIDEO")), "key_mask_003"));
  assertThat(job.path("status").asText()).isEqualTo("QUEUED");
  assertThat(job.path("request").path("resolvedInputs")).hasSize(2);
 }

 /** 音频策略只接受 SILENT 与 KEEP_SOURCE_AUDIO；保留原声整体未验收，一律以 VIDEO_002 拒绝。 */
 @Test
 void createValidatesAudioPolicy() throws Exception {
  UUID image = imageUpload(1);
  assertCode(ErrorCode.VIDEO_001, () -> service.create(1, new Create(VideoCapability.RESOURCE_ID, "prompt",
   "FIRST_FRAME", List.of(input(image, "FIRST_FRAME")), new Output("832x480", 49, 16), 42L,
   "key_audio_001", "AMBIENT", null)));
  // 能力未验收时与素材形状无关：没有源视频也报 VIDEO_002，而不是"缺少源视频"的输入错误。
  assertCode(ErrorCode.VIDEO_002, () -> service.create(1, new Create(VideoCapability.RESOURCE_ID, "prompt",
   "FIRST_FRAME", List.of(input(image, "FIRST_FRAME")), new Output("832x480", 49, 16), 42L,
   "key_audio_002", "KEEP_SOURCE_AUDIO", null)));

  // 带源视频同样拒绝：该策略在 P0 判为不可用，不能产出静音视频冒充成功。
  UUID source = seedUpload(1, "VIDEO", "video/mp4", 2048L, 6_000L, 832, 480);
  assertCode(ErrorCode.VIDEO_002, () -> service.create(1, new Create(VideoCapability.RESOURCE_ID, "prompt",
   "STRUCTURE_RESTYLE", List.of(input(source, "SOURCE_VIDEO")), new Output("832x480", 49, 16), 42L,
   "key_audio_003", "KEEP_SOURCE_AUDIO", null)));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_job", Integer.class)).isZero();
 }

 /** 模型与工作流修订标识来自配置，可随发布独立变化并写入快照。 */
 @Test
 void createHonorsConfiguredModelAndWorkflowRevisions() throws Exception {
  env.setProperty("video.model-revision", "wan-tested-rev-7");
  env.setProperty("video.workflow-revision", "video-tested-rev-9");
  UUID upload = imageUpload(1);
  var job = service.create(1, request("FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), "key_rev_0001"));
  JsonNode node = job.path("request");
  assertThat(node.path("modelRevision").asText()).isEqualTo("wan-tested-rev-7");
  assertThat(node.path("workflowRevision").asText()).isEqualTo("video-tested-rev-9");
  assertThat(jdbc.queryForObject("SELECT workflow_revision FROM video_job WHERE id=?", String.class,
   job.path("id").asText())).isEqualTo("video-tested-rev-9");
 }

 /** 任务快照写库时包含修订信息与有效提示词，且调度参数不包含主机绝对路径。 */
 @Test
 void createSnapshotKeepsRevisionsAndOnlyManagedInputIds() throws Exception {
  UUID upload = imageUpload(1);
  var job = service.create(1, request("FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), "key_snap_0001"));
  String id = job.path("id").asText();
  String snapshot = jdbc.queryForObject("SELECT request_json FROM video_job WHERE id=?", String.class, id);
  JsonNode node = json.readTree(snapshot);

  assertThat(node.path("effectivePrompt").asText()).isEqualTo("prompt-key_snap_0001");
  assertThat(node.path("width").asInt()).isEqualTo(832);
  assertThat(node.path("height").asInt()).isEqualTo(480);
  assertThat(node.path("modelRevision").asText()).isEqualTo("wan2.1-vace-1.3b-fp16");
  assertThat(node.path("workflowRevision").asText()).isEqualTo("video-vace-1.3b-v1");
  assertThat(node.path("audioPolicy").asText()).isEqualTo("SILENT");
  assertThat(node.path("output").path("frames").asInt()).isEqualTo(49);
  assertThat(node.path("output").path("fps").asInt()).isEqualTo(16);
  assertThat(node.path("output").path("size").asText()).isEqualTo("832x480");
  assertThat(node.path("seed").asLong()).isEqualTo(42L);
  Map<String, Object> stored = jdbc.queryForMap(
   "SELECT frames,fps,seed,width,height,size FROM video_job WHERE id=?", id);
  assertThat(((Number) stored.get("frames")).intValue()).isEqualTo(49);
  assertThat(((Number) stored.get("fps")).intValue()).isEqualTo(16);
  assertThat(((Number) stored.get("seed")).longValue()).isEqualTo(42L);
  assertThat(((Number) stored.get("width")).intValue()).isEqualTo(832);
  assertThat(((Number) stored.get("height")).intValue()).isEqualTo(480);
  assertThat(stored.get("size")).isEqualTo("832x480");

  JsonNode entry = node.path("resolvedInputs").get(0);
  assertThat(entry.size()).isEqualTo(4);
  assertThat(entry.path("inputId").asText()).isNotBlank();
  assertThat(entry.path("uploadId").asText()).isEqualTo(upload.toString());
  assertThat(entry.path("role").asText()).isEqualTo("FIRST_FRAME");
  assertThat(entry.path("sha256").asText()).hasSize(64);
  assertThat(snapshot).doesNotContain(root.toAbsolutePath().toString());
  assertThat(snapshot).doesNotContain(root.resolve("uploads").toString());

  // 返回给前端的视图同样只暴露受管标识。
  assertThat(job.path("request").path("resolvedInputs").get(0).size()).isEqualTo(4);
  assertThat(job.toString()).doesNotContain(root.toAbsolutePath().toString());
 }

 /** 未提供音频策略时落库为 SILENT，并可由能力目录读取模型清单。 */
 @Test
 void createDefaultsAudioPolicyToSilent() throws Exception {
  UUID upload = imageUpload(1);
  var job = service.create(1, request("FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), "key_default_01"));
  assertThat(jdbc.queryForObject("SELECT audio_policy FROM video_job WHERE id=?", String.class,
   job.path("id").asText())).isEqualTo("SILENT");
  assertThat(service.models()).isNotEmpty();
 }

 /** 查询他人任务以 VIDEO_009 拒绝，list 只返回本人作品。 */
 @Test
 void getAndListAreOwnedByTheRequestingAccount() throws Exception {
  UUID upload = imageUpload(1);
  String id = service.create(1, request("FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), "key_get_0001"))
   .path("id").asText();
  assertThat(service.get(1, id).path("id").asText()).isEqualTo(id);
  assertCode(ErrorCode.VIDEO_009, () -> service.get(2, id));
  assertCode(ErrorCode.VIDEO_009, () -> service.get(1, UUID.randomUUID().toString()));
  assertThat(service.list(1, 0)).hasSize(1);
  assertThat(service.list(2, 0)).isEmpty();
 }

 /** 分页越界以 VIDEO_001 拒绝。 */
 @Test
 void listRejectsOutOfRangePage() {
  assertCode(ErrorCode.VIDEO_001, () -> service.list(1, 10_001));
  assertCode(ErrorCode.VIDEO_001, () -> service.list(1, -1));
  assertThat(service.list(1, 0)).isEmpty();
  assertThat(service.list(1, 10_000)).isEmpty();
 }

 /** 未派发任务直接落 CANCELLED，已派发任务落 CANCEL_REQUESTED。 */
 @Test
 void cancelDependsOnWhetherDispatchStarted() throws Exception {
  UUID upload = imageUpload(1);
  String queued = service.create(1, request("FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), "key_cancel_01"))
   .path("id").asText();
  assertThat(service.cancel(1, queued).path("status").asText()).isEqualTo("CANCELLED");
  assertThat(jdbc.queryForObject("SELECT status FROM video_job WHERE id=?", String.class, queued))
   .isEqualTo("CANCELLED");

  String dispatched = service.create(1,
   request("FIRST_FRAME", List.of(input(upload, "FIRST_FRAME")), "key_cancel_02")).path("id").asText();
  jdbc.update("UPDATE video_job SET task_id=?,dispatch_started=TRUE WHERE id=?",
   UUID.randomUUID().toString(), dispatched);
  assertThat(service.cancel(1, dispatched).path("status").asText()).isEqualTo("CANCEL_REQUESTED");

  assertCode(ErrorCode.VIDEO_009, () -> service.cancel(2, dispatched));
 }

 /**
  * 按所有者与任务标识读取任务行，并补上与生产查询一致的耗时列。
  *
  * @param owner 任务所有者
  * @param id 任务标识
  * @return 任务行
  */
 private Map<String, Object> jobRow(long owner, String id) {
  List<Map<String, Object>> rows = jdbc.queryForList(
   "SELECT * FROM video_job WHERE id=? AND owner_id=?", id, owner);
  if (rows.isEmpty()) throw new VideoException(ErrorCode.VIDEO_009);
  Map<String, Object> row = rows.getFirst();
  row.put("elapsed_millis", elapsedMillis(row));
  return row;
 }

 /**
  * 执行任务查询并补齐耗时列。
  *
  * @param sql 参数化查询
  * @param args 查询参数
  * @return 任务行列表
  */
 private List<Map<String, Object>> jobRows(String sql, Object... args) {
  List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
  rows.forEach(row -> row.put("elapsed_millis", elapsedMillis(row)));
  return rows;
 }

 /**
  * 按状态计算排队毫秒数：终态固定到 updated_at。
  *
  * @param row 任务行
  * @return 毫秒数
  */
 private static long elapsedMillis(Map<String, Object> row) {
  long created = ((java.sql.Timestamp) row.get("created_at")).getTime();
  String status = row.get("status").toString();
  long end = OPEN_STATUS.contains(status)
   ? System.currentTimeMillis() : ((java.sql.Timestamp) row.get("updated_at")).getTime();
  return Math.max(0, end - created);
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
  * 构造 FIRST_FRAME 之外的固定形状请求。
  *
  * @param mode 模式名
  * @param inputs 输入素材
  * @param key 幂等键
  * @return 生成请求
  */
 private static Create request(String mode, List<Input> inputs, String key) {
  return new Create(VideoCapability.RESOURCE_ID, "prompt-" + key, mode, inputs,
   new Output("832x480", 49, 16), 42L, key, null, null);
 }

 /**
  * 构造单输入素材。
  *
  * @param uploadId 上传标识
  * @param role 角色
  * @return 输入素材
  */
 private static Input input(UUID uploadId, String role) {
  return new Input(uploadId, role, null, null, null);
 }

 /**
  * 构造带裁剪窗口的输入素材。
  *
  * @param uploadId 上传标识
  * @param role 角色
  * @param start 起点毫秒
  * @param end 终点毫秒
  * @return 输入素材
  */
 private static Input trimmed(UUID uploadId, String role, Long start, Long end) {
  return new Input(uploadId, role, null, start, end);
 }

 /**
  * 直接落库一条本人素材，用于构造类型不匹配与裁剪场景。
  *
  * @param owner 所有者
  * @param kind IMAGE 或 VIDEO
  * @param mime MIME 类型
  * @param size 字节数
  * @param durationMs 视频时长
  * @param width 宽度
  * @param height 高度
  * @return 上传标识
  */
 private UUID seedUpload(long owner, String kind, String mime, long size, Long durationMs, Integer width,
                         Integer height) {
  String id = UUID.randomUUID().toString();
  jdbc.update("INSERT INTO video_upload(id,owner_id,kind,mime_type,size_bytes,content_sha256,duration_ms,"
   + "width,height) VALUES(?,?,?,?,?,?,?,?,?)", id, owner, kind, mime, size, "a".repeat(64), durationMs,
   width, height);
  return UUID.fromString(id);
 }

 /**
  * 通过真实上传通道登记一张 PNG。
  *
  * @param owner 所有者
  * @return 上传标识
  * @throws IOException 图片编码失败
  */
 private UUID imageUpload(long owner) throws IOException {
  return service.upload(owner, "IMAGE", pngBytes()).id();
 }

 /**
  * 列出受管上传目录中的文件。
  *
  * @return 文件列表
  * @throws IOException 目录读取失败
  */
 private List<Path> uploadFiles() throws IOException {
  try (var stream = Files.list(root.resolve("uploads"))) {
   return stream.toList();
  }
 }

 /**
  * 生成一张可被 ImageIO 解码的 PNG。
  *
  * @return PNG 字节
  * @throws IOException 图片编码失败
  */
 private static byte[] pngBytes() throws IOException {
  return encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png");
 }

 /**
  * 生成一张可被 ImageIO 解码的 JPEG。
  *
  * @return JPEG 字节
  * @throws IOException 图片编码失败
  */
 private static byte[] jpegBytes() throws IOException {
  return encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg");
 }

 /**
  * 按指定格式编码图片。
  *
  * @param image 图片
  * @param format 格式名
  * @return 图片字节
  * @throws IOException 图片编码失败
  */
 private static byte[] encode(BufferedImage image, String format) throws IOException {
  ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  ImageIO.write(image, format, bytes);
  return bytes.toByteArray();
 }
}
