package com.yuyutian.mytools.video.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.video.common.ErrorCode;
import com.yuyutian.mytools.video.common.VideoException;
import com.yuyutian.mytools.video.model.VideoModels.Create;
import com.yuyutian.mytools.video.model.VideoModels.Input;
import com.yuyutian.mytools.video.model.VideoModels.ModeSpec;
import com.yuyutian.mytools.video.model.VideoModels.RoleSpec;
import com.yuyutian.mytools.video.model.VideoModels.Uploaded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
/** 视频任务、用户隔离、持久对账与受管文件读取。 */
@Service
public class VideoService {
 private static final Logger LOG = LoggerFactory.getLogger(VideoService.class);
 /** 终态集合。 */
 private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "PARTIAL_SUCCESS");
 /** 图片素材上限：20MB，解码不超过 16MP。 */
 private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
 private static final long MAX_IMAGE_PIXELS = 16_000_000L;
 /** 视频素材上限：200MB、60 秒；片段窗口不超过 5 秒。 */
 private static final long MAX_VIDEO_BYTES = 200L * 1024 * 1024;
 private static final long MAX_VIDEO_MS = 60_000L;
 private static final long MAX_TRIM_MS = 5_000L;
 private final VideoStore store;
 private final VideoCapability capability;
 private final VideoUpstream upstream;
 private final VideoProbe probe;
 private final Environment env;
 private final Path root;
 /** 注入共享存储、能力目录与上游客户端，并初始化受管目录。 */
 public VideoService(VideoStore store, VideoCapability capability, VideoUpstream upstream, VideoProbe probe,
                     Environment env) throws IOException {
  this.store = store; this.capability = capability; this.upstream = upstream; this.probe = probe; this.env = env;
  root = Path.of(env.getProperty("video.root", "./runtime/video-generation")).toAbsolutePath().normalize();
  Files.createDirectories(root.resolve("uploads"));
  Files.createDirectories(root.resolve("outputs"));
  Files.createDirectories(root.resolve("work"));
 }
 /** 返回能力目录。 */
 public List<com.yuyutian.mytools.video.model.VideoModels.Model> models() { return capability.models(); }
 /** 保存本人上传素材：类型由服务端实测，不信任客户端申报。 */
 public Uploaded upload(long owner, String kind, byte[] bytes) throws IOException {
  if (bytes.length == 0) throw new VideoException(ErrorCode.VIDEO_001);
  String id = UUID.randomUUID().toString();
  Path target = root.resolve("uploads").resolve(id);
  if (kind.equals("IMAGE")) {
   String mime = inspectImage(bytes);
   Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
   store.jdbc().update("INSERT INTO video_upload(id,owner_id,kind,mime_type,size_bytes,content_sha256) "
    + "VALUES(?,?,?,?,?,?)", id, owner, kind, mime, bytes.length, sha(bytes));
   return new Uploaded(UUID.fromString(id), kind, mime, bytes.length, null, null, null);
  }
  if (!kind.equals("VIDEO")) throw new VideoException(ErrorCode.VIDEO_001);
  if (bytes.length > MAX_VIDEO_BYTES) throw new VideoException(ErrorCode.VIDEO_001);
  Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
  VideoProbe.Probe result = probe.probe(target);
  if (result.durationMs() <= 0 || result.durationMs() > MAX_VIDEO_MS) {
   Files.deleteIfExists(target);
   throw new VideoException(ErrorCode.VIDEO_001);
  }
  store.jdbc().update("INSERT INTO video_upload(id,owner_id,kind,mime_type,size_bytes,content_sha256,"
   + "duration_ms,width,height) VALUES(?,?,?,?,?,?,?,?,?)", id, owner, kind, "video/mp4", bytes.length,
   sha(bytes), result.durationMs(), result.width(), result.height());
  return new Uploaded(UUID.fromString(id), kind, "video/mp4", bytes.length, result.durationMs(),
   result.width(), result.height());
 }
 /** 创建持久任务；幂等重试不受之后能力开关变化影响。 */
 @Transactional
 public synchronized ObjectNode create(long owner, Create request) {
  ObjectNode submitted = store.json().valueToTree(request);
  for (String field : List.of("parentJobId", "audioPolicy")) {
   if (submitted.path(field).isNull() || submitted.path(field).asText("").isBlank()) submitted.remove(field);
  }
  String encoded = store.encode(submitted);
  String hash = sha(encoded.getBytes(StandardCharsets.UTF_8));
  var existing = store.byKey(owner, request.idempotencyKey());
  if (!existing.isEmpty()) return replay(existing.getFirst(), hash);
  ModeSpec spec = capability.require(request.resourceId(), request.mode());
  validateShape(spec, request);
  if (request.parentJobId() != null) store.job(owner, request.parentJobId().toString());
  String audioPolicy = request.audioPolicy() == null || request.audioPolicy().isBlank()
   ? "SILENT" : request.audioPolicy();
  // 未定义取值属于输入错误；保留原声属于 P0 判为不可用的能力，两者必须给出不同错误码。
  if (!Set.of("SILENT", "KEEP_SOURCE_AUDIO").contains(audioPolicy)) throw new VideoException(ErrorCode.VIDEO_001);
  if (!audioPolicy.equals("SILENT")) throw new VideoException(ErrorCode.VIDEO_002);
  String width = request.output().size().split("x")[0];
  String height = request.output().size().split("x")[1];
  String id = UUID.randomUUID().toString();
  String modelRevision = env.getProperty("video.model-revision", "wan2.1-vace-1.3b-fp16");
  // 工作流规格是一整套版本化文件，索引摘要由执行器侧固定，因此这里只有一个版本标识。
  String workflowRevision = env.getProperty("video.workflow-revision", "video-vace-1.3b-v1");
  ObjectNode snapshot = submitted.deepCopy();
  snapshot.put("effectivePrompt", request.prompt());
  snapshot.put("width", Integer.parseInt(width));
  snapshot.put("height", Integer.parseInt(height));
  snapshot.put("modelRevision", modelRevision);
  snapshot.put("workflowRevision", workflowRevision);
  snapshot.put("audioPolicy", audioPolicy);
  ArrayNode resolved = snapshot.putArray("resolvedInputs");
  try {
   store.jdbc().update("INSERT INTO video_job(id,owner_id,idempotency_key,request_sha256,request_json,"
    + "original_prompt,effective_prompt,mode,size,width,height,frames,fps,seed,audio_policy,"
    + "model_revision,workflow_revision,status,parent_job_id) "
    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    id, owner, request.idempotencyKey(), hash, store.encode(snapshot), request.prompt(), request.prompt(),
    request.mode(), request.output().size(), Integer.parseInt(width), Integer.parseInt(height),
    request.output().frames(), request.output().fps(), request.seed(), audioPolicy, modelRevision,
    workflowRevision, "QUEUED", request.parentJobId() == null ? null : request.parentJobId().toString());
  } catch (DuplicateKeyException e) {
   // 并发提交同一幂等键时另一事务可能尚未提交，此处不能假设一定读得到那一行。
   var concurrent = store.byKey(owner, request.idempotencyKey());
   if (concurrent.isEmpty()) throw new VideoException(ErrorCode.VIDEO_008);
   return replay(concurrent.getFirst(), hash);
  }
  int ordinal = 0;
  for (Input item : request.inputs()) {
   Map<String, Object> upload = ownedUpload(owner, item.uploadId().toString());
   String roleKind = roleKind(spec, item.role());
   if (!roleKind.equals(upload.get("kind").toString())) throw new VideoException(ErrorCode.VIDEO_001);
   Long start = item.trimStartMs();
   Long end = item.trimEndMs();
   if (start != null || end != null) {
    // 裁剪只对视频意义明确，并且必须落在素材时长内、窗口不超过 5 秒。
    if (!roleKind.equals("VIDEO")) throw new VideoException(ErrorCode.VIDEO_001);
    long duration = ((Number) upload.get("duration_ms")).longValue();
    long from = start == null ? 0 : start;
    long to = end == null ? Math.min(duration, from + MAX_TRIM_MS) : end;
    if (from < 0 || to <= from || to > duration || to - from > MAX_TRIM_MS) throw new VideoException(ErrorCode.VIDEO_001);
    start = from; end = to;
   }
   String inputId = UUID.randomUUID().toString();
   store.jdbc().update("INSERT INTO video_input(id,job_id,upload_id,role,description,ordinal,"
    + "content_sha256,trim_start_ms,trim_end_ms) VALUES(?,?,?,?,?,?,?,?,?)", inputId, id,
    item.uploadId().toString(), item.role(), item.description(), ordinal,
    upload.get("content_sha256").toString(), start, end);
   ObjectNode entry = resolved.addObject();
   // 只登记受管素材标识与摘要；主机绝对路径不写入调度参数，执行器按根目录与 uploadId 自行定位。
   entry.put("inputId", inputId).put("uploadId", item.uploadId().toString()).put("role", item.role())
    .put("sha256", upload.get("content_sha256").toString());
   if (start != null) entry.put("trimStartMs", start);
   if (end != null) entry.put("trimEndMs", end);
   ordinal++;
  }
  store.jdbc().update("UPDATE video_job SET request_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
   store.encode(snapshot), id);
  store.event(id, "VALIDATE", "accepted");
  return view(store.job(owner, id));
 }
 /** 查询用户任务。 */
 public ObjectNode get(long owner, String id) { return view(store.job(owner, id)); }
 /** 查询用户作品与任务历史。 */
 public List<ObjectNode> list(long owner, int page) {
  if (page < 0 || page > 10000) throw new VideoException(ErrorCode.VIDEO_001);
  return store.list(owner, page).stream().map(this::view).toList();
 }
 /** 取消请求持久化后由后台与调度器对账。 */
 public synchronized ObjectNode cancel(long owner, String id) {
  store.job(owner, id);
  store.jdbc().update("UPDATE video_job SET status=CASE WHEN task_id IS NULL AND dispatch_started=FALSE "
   + "THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,updated_at=CURRENT_TIMESTAMP "
   + "WHERE id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')", id, owner);
  return get(owner, id);
 }
 /** 读取本人上传素材。 */
 public byte[] input(long owner, String id) throws IOException {
  ownedUpload(owner, id);
  return Files.readAllBytes(safe(root.resolve("uploads").resolve(id)));
 }
 /** 本人素材的字节数，供 Range 请求计算区间。 */
 public long inputSize(long owner, String id) throws IOException {
  ownedUpload(owner, id);
  return Files.size(safe(root.resolve("uploads").resolve(id)));
 }
 /** 读取本人素材的字节区间；原片对比播放只需要窗口内的数据。 */
 public byte[] inputRange(long owner, String id, long start, int length) throws IOException {
  ownedUpload(owner, id);
  return readRange(safe(root.resolve("uploads").resolve(id)), start, length);
 }
 /** 本人成片的字节数。 */
 public long videoSize(long owner, String id) throws IOException {
  requireOutput(owner, id, "VIDEO");
  return Files.size(safe(root.resolve("outputs").resolve(id).resolve("video.mp4")));
 }
 /** 读取本人成片的字节区间，避免为一次播放把整段成片读进堆。 */
 public byte[] videoRange(long owner, String id, long start, int length) throws IOException {
  requireOutput(owner, id, "VIDEO");
  return readRange(safe(root.resolve("outputs").resolve(id).resolve("video.mp4")), start, length);
 }
 /** 按窗口读取文件内容；窗口由调用方保证落在文件长度内。 */
 private byte[] readRange(Path path, long start, int length) throws IOException {
  byte[] buffer = new byte[length];
  try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
   channel.position(start);
   var stream = java.nio.ByteBuffer.wrap(buffer);
   while (stream.hasRemaining()) {
    if (channel.read(stream) < 0) break;
   }
   if (stream.hasRemaining()) throw new VideoException(ErrorCode.VIDEO_009);
  }
  return buffer;
 }
 /** 读取本人已登记的成片。 */
 public byte[] video(long owner, String id) throws IOException {
  requireOutput(owner, id, "VIDEO");
  return Files.readAllBytes(safe(root.resolve("outputs").resolve(id).resolve("video.mp4")));
 }
 /** 读取本人成片封面。 */
 public byte[] cover(long owner, String id) throws IOException {
  requireOutput(owner, id, "COVER");
  return Files.readAllBytes(safe(root.resolve("outputs").resolve(id).resolve("cover.png")));
 }
 /** 后台派发与结果对账采用稳定幂等键，可在服务重启后恢复。 */
 @Scheduled(fixedDelayString = "${video.reconcile-ms:5000}")
 public void reconcile() {
  for (var row : store.pending()) {
   try { advance(row); } catch (Exception e) {
    // 保留待对账状态，避免短暂故障导致重复生成或丢失已产出视频。
    LOG.warn("video reconcile deferred: {}", e.getClass().getSimpleName());
    store.jdbc().update("UPDATE video_job SET error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
     ErrorCode.VIDEO_006.name(), row.get("id"));
   }
  }
 }
 private void advance(Map<String, Object> row) throws IOException {
  String id = row.get("id").toString();
  long owner = ((Number) row.get("owner_id")).longValue();
  String task = row.get("task_id") == null ? null : row.get("task_id").toString();
  if (task == null) {
   int admitted = store.jdbc().update("UPDATE video_job SET dispatch_started=TRUE "
    + "WHERE id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')", id);
   if (admitted == 0) return;
   ObjectNode parameters = (ObjectNode) parse(row.get("request_json"));
   parameters.put("jobId", id);
   // 调度器只接收已验收的契约字段；原始描述与素材列表留在本服务库里，快照不外泄内部结构。
   parameters.remove(List.of("effectivePrompt", "inputs"));
   JsonNode created = upstream.scheduler("/api/v1/task-instances", HttpMethod.POST, Map.of(
    "taskName", "video_generate", "idempotencyKey", "video:" + id, "businessType", "VIDEO_GENERATION",
    "businessId", id, "priority", 40, "parameters", parameters));
   task = UUID.fromString(created.path("id").asText()).toString();
   store.jdbc().update("UPDATE video_job SET task_id=?,error_code=NULL WHERE id=?", task, id);
   store.event(id, "WAITING_RESOURCE", "dispatched");
  }
  if (store.job(owner, id).get("status").toString().equals("CANCEL_REQUESTED")) {
   upstream.scheduler("/api/v1/task-instances/" + task + "/cancel", HttpMethod.POST, Map.of());
  }
  JsonNode summary = upstream.scheduler("/api/v1/task-instances/" + task, HttpMethod.GET, null);
  String terminal = summary.path("status").asText();
  if (!TERMINAL.contains(terminal)) {
   String status = store.job(owner, id).get("status").toString();
   if (!status.equals("CANCEL_REQUESTED")) {
    String mapped = terminal.equals("RUNNING") ? "RUNNING" : "QUEUED";
    store.jdbc().update("UPDATE video_job SET status=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP "
     + "WHERE id=? AND status IN ('QUEUED','RUNNING')", mapped, id);
    store.event(id, mapped, terminal);
   }
   return;
  }
  persist(owner, id, row, terminal);
 }
 /** 生成成功后必须完成资产登记才算成功，否则保留 PERSISTING 以便重试。 */
 private void persist(long owner, String id, Map<String, Object> row, String terminal) throws IOException {
  store.jdbc().update("UPDATE video_job SET status='PERSISTING' WHERE id=?", id);
  Path folder = root.resolve("outputs").resolve(id);
  Path video = folder.resolve("video.mp4");
  Path cover = folder.resolve("cover.png");
  if (!Files.isRegularFile(video, LinkOption.NOFOLLOW_LINKS)) {
   String status = terminal.equals("CANCELLED") ? "CANCELLED" : "FAILED";
   store.jdbc().update("UPDATE video_job SET status=?,error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
    status, status.equals("FAILED") ? ErrorCode.VIDEO_004.name() : null, id);
   store.event(id, status, terminal);
   return;
  }
  byte[] bytes = Files.readAllBytes(safe(video));
  // 执行器的 result.json 是可选证据：缺失或不完整都不能让已产出的成片卡在 PERSISTING。
  JsonNode produced = store.json().createObjectNode();
  Path evidence = folder.resolve("result.json");
  if (Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS)) {
   try {
    produced = parse(Files.readString(safe(evidence)));
   } catch (RuntimeException e) {
    LOG.warn("video result evidence unreadable: {}", e.getClass().getSimpleName());
   }
  }
  register(owner, id, "VIDEO", video, bytes, "video/mp4");
  if (Files.isRegularFile(cover, LinkOption.NOFOLLOW_LINKS)) {
   register(owner, id, "COVER", cover, Files.readAllBytes(safe(cover)), "image/png");
  }
  // 参数里已有帧数与尺寸；真实时长以探针读到的成片为准，两者都不依赖执行器回报。
  ObjectNode result = store.json().createObjectNode();
  int frames = produced.path("frames").asInt(((Number) row.get("frames")).intValue());
  int fps = produced.path("fps").asInt(((Number) row.get("fps")).intValue());
  result.put("frames", frames);
  result.put("fps", fps);
  result.put("durationMs", produced.path("durationMs").asLong(probedMillis(video, frames, fps)));
  result.put("width", produced.path("width").asInt(((Number) row.get("width")).intValue()));
  result.put("height", produced.path("height").asInt(((Number) row.get("height")).intValue()));
  JsonNode peak = produced.path("resourcePeak");
  ObjectNode resourcePeak = peak.isObject() ? (ObjectNode) peak : store.json().createObjectNode();
  result.set("resourcePeak", resourcePeak);
  result.put("hasAudio", false);
  store.jdbc().update("UPDATE video_job SET status='SUCCEEDED',result_json=?,resource_peak_json=?,"
   + "error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", store.encode(result),
   store.encode(resourcePeak), id);
  store.event(id, "PUBLISHING", "assets registered");
 }
 /** 以探针读取成片时长；探测失败时按帧数换算，绝不因为证据缺失而阻断发布。 */
 private long probedMillis(Path video, int frames, int fps) {
  try {
   long duration = probe.probe(video).durationMs();
   if (duration > 0) return duration;
  } catch (Exception e) {
   LOG.warn("video probe failed: {}", e.getClass().getSimpleName());
  }
  return fps <= 0 ? 0 : frames * 1000L / fps;
 }
 private void register(long owner, String id, String kind, Path path, byte[] bytes, String mime) {
  // 资产来源对 (sourceType,sourceBusinessId) 唯一：成片沿用任务编号，封面必须用独立编号，
  // 否则第二条登记必然以 409 冲突收场。
  String sourceId = kind.equals("VIDEO") ? id : id + ":" + kind;
  JsonNode asset = upstream.asset(Map.of("ownerId", owner, "idempotencyKey", "video:" + id + ":" + kind,
   "sourceType", "VIDEO_GENERATION", "sourceBusinessId", sourceId, "contentSha256", sha(bytes),
   "sizeBytes", bytes.length, "mimeType", mime, "location", Map.of(
    "idempotencyKey", "video-location:" + id + ":" + kind, "providerType", "LOCAL",
    "storageUri", path.toUri().toString(), "providerVersion", "1")));
  String assetId = asset.path("id").asText();
  if (assetId.isBlank()) throw new VideoException(ErrorCode.VIDEO_006);
  store.jdbc().update("INSERT INTO video_output(id,job_id,kind,asset_id,storage_uri,content_sha256,"
   + "size_bytes,mime_type) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE asset_id=VALUES(asset_id)",
   UUID.randomUUID().toString(), id, kind, assetId, path.toUri().toString(), sha(bytes), bytes.length, mime);
 }
 private void validateShape(ModeSpec spec, Create request) {
  if (spec.requiresPrompt() && request.prompt().isBlank()) throw new VideoException(ErrorCode.VIDEO_001);
  if (!capability.allowsFrames(spec, request.output().frames())) throw new VideoException(ErrorCode.VIDEO_002);
  if (!VideoCapability.SIZES.contains(request.output().size())) throw new VideoException(ErrorCode.VIDEO_002);
  if (!List.of(16).contains(request.output().fps())) throw new VideoException(ErrorCode.VIDEO_002);
  List<Input> inputs = request.inputs();
  if (inputs.size() < spec.minInputs() || inputs.size() > spec.maxInputs()) throw new VideoException(ErrorCode.VIDEO_001);
  Set<UUID> unique = new HashSet<>();
  for (Input item : inputs) if (!unique.add(item.uploadId())) throw new VideoException(ErrorCode.VIDEO_001);
  List<String> expected = spec.roles().stream().map(RoleSpec::name).toList();
  if (spec.mode().equals("FIRST_LAST_FRAMES")) {
   // 首尾帧顺序有语义，必须恰好按 FIRST_FRAME、LAST_FRAME 提交。
   List<String> actual = inputs.stream().map(Input::role).toList();
   if (!actual.equals(expected)) throw new VideoException(ErrorCode.VIDEO_001);
   return;
  }
  if (spec.mode().equals("MASKED_EDIT")) {
   if (!inputs.getFirst().role().equals("SOURCE_VIDEO")) throw new VideoException(ErrorCode.VIDEO_001);
   if (inputs.size() == 2 && !inputs.get(1).role().equals("MASK_VIDEO")) throw new VideoException(ErrorCode.VIDEO_001);
   return;
  }
  for (Input item : inputs) if (!expected.contains(item.role())) throw new VideoException(ErrorCode.VIDEO_001);
 }
 private String roleKind(ModeSpec spec, String role) {
  return spec.roles().stream().filter(item -> item.name().equals(role)).findFirst()
   .orElseThrow(() -> new VideoException(ErrorCode.VIDEO_001)).kind();
 }
 private ObjectNode replay(Map<String, Object> row, String hash) {
  if (!hash.equals(row.get("request_sha256"))) throw new VideoException(ErrorCode.VIDEO_008);
  return view(row);
 }
 private Map<String, Object> ownedUpload(long owner, String id) {
  List<Map<String, Object>> rows = store.jdbc().queryForList(
   "SELECT * FROM video_upload WHERE id=? AND owner_id=?", id, owner);
  if (rows.isEmpty()) throw new VideoException(ErrorCode.VIDEO_009);
  return rows.getFirst();
 }
 private void requireOutput(long owner, String id, String kind) {
  store.job(owner, id);
  Integer count = store.jdbc().queryForObject(
   "SELECT COUNT(*) FROM video_output WHERE job_id=? AND kind=? AND asset_id IS NOT NULL", Integer.class, id, kind);
  if (count == null || count == 0) throw new VideoException(ErrorCode.VIDEO_009);
 }
 private ObjectNode view(Map<String, Object> row) {
  ObjectNode node = store.json().createObjectNode();
  for (String key : List.of("id", "status", "mode")) node.put(key, row.get(key).toString());
  node.put("createdAt", row.get("created_at").toString());
  node.put("errorCode", row.get("error_code") == null ? "" : row.get("error_code").toString());
  node.put("elapsedMillis", Math.max(0, ((Number) row.get("elapsed_millis")).longValue()));
  node.set("request", parse(row.get("request_json")));
  node.set("result", parse(row.get("result_json")));
  node.set("stages", stages(row.get("id").toString()));
  return node;
 }
 private ArrayNode stages(String jobId) {
  ArrayNode array = store.json().createArrayNode();
  for (Map<String, Object> row : store.jdbc().queryForList(
   "SELECT stage,occurred_at FROM video_job_event WHERE job_id=? ORDER BY occurred_at,stage", jobId)) {
   array.addObject().put("stage", row.get("stage").toString()).put("at", row.get("occurred_at").toString());
  }
  return array;
 }
 private JsonNode parse(Object value) {
  try {
   return value == null ? store.json().createObjectNode() : store.json().readTree(value.toString());
  } catch (IOException e) {
   throw new VideoException(ErrorCode.VIDEO_001);
  }
 }
 private Path safe(Path path) throws IOException {
  for (Path part = root; part != null && path.startsWith(part); ) {
   if (Files.isSymbolicLink(part)) throw new VideoException(ErrorCode.VIDEO_001);
   if (part.equals(path)) break;
   part = part.resolve(path.getName(part.getNameCount()));
  }
  if (!path.toRealPath().startsWith(root.toRealPath()) || Files.size(path) > MAX_VIDEO_BYTES) {
   throw new VideoException(ErrorCode.VIDEO_001);
  }
  return path;
 }
 private String inspectImage(byte[] bytes) throws IOException {
  if (bytes.length > MAX_IMAGE_BYTES) throw new VideoException(ErrorCode.VIDEO_001);
  try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
   var readers = ImageIO.getImageReaders(stream);
   if (!readers.hasNext()) throw new VideoException(ErrorCode.VIDEO_001);
   var reader = readers.next();
   try {
    reader.setInput(stream);
    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
    if (!Set.of("png", "jpeg", "jpg").contains(format)
     || (long) reader.getWidth(0) * reader.getHeight(0) > MAX_IMAGE_PIXELS) {
     throw new VideoException(ErrorCode.VIDEO_001);
    }
    if (reader.read(0) == null) throw new VideoException(ErrorCode.VIDEO_001);
    return format.equals("png") ? "image/png" : "image/jpeg";
   } finally { reader.dispose(); }
  }
 }
 private static String sha(byte[] value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
  catch (Exception e) { throw new IllegalStateException(e); }
 }
}
