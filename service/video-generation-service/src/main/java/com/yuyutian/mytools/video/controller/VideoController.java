package com.yuyutian.mytools.video.controller;
import com.yuyutian.mytools.video.common.ByteRange;
import com.yuyutian.mytools.video.common.ErrorCode;
import com.yuyutian.mytools.video.common.VideoException;
import com.yuyutian.mytools.video.model.VideoModels.Create;
import com.yuyutian.mytools.video.service.VideoService;
import jakarta.validation.Valid;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
/** 只接受网关认证与可信所有者的视频接口。 */
@RestController
@RequestMapping("/internal/v1/videos")
public class VideoController {
 private final VideoService service;
 private final Environment env;
 /** 注入视频业务服务。 */
 public VideoController(VideoService service, Environment env) { this.service = service; this.env = env; }
 /** 返回逐模式的能力与验收状态。 */
 @GetMapping("/models")
 public Object models(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                      @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner) {
  authorize(auth, owner);
  return service.models();
 }
 /** 接收原始字节的图片素材，避免 base64 放大。 */
 @PostMapping(value = "/uploads/image", consumes = MediaType.ALL_VALUE)
 public Object uploadImage(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                           @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                           @RequestBody byte[] body) throws Exception {
  authorize(auth, owner);
  return service.upload(owner, "IMAGE", body);
 }
 /** 接收原始字节的视频素材；大小与时长由服务端实测。 */
 @PostMapping(value = "/uploads/video", consumes = MediaType.ALL_VALUE)
 public Object uploadVideo(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                           @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                           @RequestBody byte[] body) throws Exception {
  authorize(auth, owner);
  return service.upload(owner, "VIDEO", body);
 }
 /** 创建幂等任务。 */
 @PostMapping("/jobs")
 public Object create(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                      @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                      @Valid @RequestBody Create body) {
  authorize(auth, owner);
  return service.create(owner, body);
 }
 /** 查询本人任务。 */
 @GetMapping("/jobs/{id}")
 public Object get(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                   @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                   @PathVariable UUID id) {
  authorize(auth, owner);
  return service.get(owner, id.toString());
 }
 /** 请求取消本人任务。 */
 @PostMapping("/jobs/{id}/cancel")
 public Object cancel(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                      @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                      @PathVariable UUID id) {
  authorize(auth, owner);
  return service.cancel(owner, id.toString());
 }
 /** 分页读取本人作品历史。 */
 @GetMapping("/works")
 public Object list(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                    @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                    @RequestParam(defaultValue = "0") int page) {
  authorize(auth, owner);
  return service.list(owner, page);
 }
 /** 读取本人上传素材；同样支持 Range，原片对比时可以只取窗口。 */
 @GetMapping("/uploads/{id}")
 public ResponseEntity<byte[]> input(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                                     @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                                     @RequestHeader(value = "Range", required = false) String range,
                                     @PathVariable UUID id) throws Exception {
  authorize(auth, owner);
  return ranged(service.inputSize(owner, id.toString()), range, null,
   (start, length) -> service.inputRange(owner, id.toString(), start, length));
 }
 /** 流式读取本人成片：按 Range 只读请求窗口并回 206，供播放器边下边播。 */
 @GetMapping("/jobs/{id}/video")
 public ResponseEntity<byte[]> video(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                                     @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                                     @RequestHeader(value = "Range", required = false) String range,
                                     @PathVariable UUID id) throws Exception {
  authorize(auth, owner);
  return ranged(service.videoSize(owner, id.toString()), range, MediaType.valueOf("video/mp4"),
   (start, length) -> service.videoRange(owner, id.toString(), start, length));
 }
 /** 下载本人成片封面。 */
 @GetMapping("/jobs/{id}/cover")
 public ResponseEntity<byte[]> cover(@RequestHeader(value = "Authorization", defaultValue = "") String auth,
                                     @RequestHeader(value = "X-Owner-Id", defaultValue = "0") long owner,
                                     @PathVariable UUID id) throws Exception {
  authorize(auth, owner);
  return binary(service.cover(owner, id.toString()), MediaType.IMAGE_PNG);
 }
 /** 按 Range 组装响应：无区间回 200、命中回 206、越界回 416；切片由调用方只读所需窗口。 */
 private ResponseEntity<byte[]> ranged(long length, String header, MediaType declared, SliceReader reader)
  throws Exception {
  ByteRange parsed = ByteRange.parse(header, length);
  if (parsed.kind() == ByteRange.Kind.UNSATISFIABLE) {
   return ResponseEntity.status(416).header("Content-Range", "bytes */" + length)
    .header("Accept-Ranges", "bytes").build();
  }
  boolean partial = parsed.kind() == ByteRange.Kind.SATISFIED;
  byte[] body = partial ? reader.read(parsed.start(), (int) parsed.size()) : reader.read(0, (int) length);
  ResponseEntity.BodyBuilder builder = partial ? ResponseEntity.status(206) : ResponseEntity.ok();
  builder.header("Accept-Ranges", "bytes").header("Cache-Control", "no-store")
   .header("X-Content-Type-Options", "nosniff");
  if (partial) builder.header("Content-Range", parsed.contentRange(length));
  MediaType type = declared;
  if (type == null) {
   type = body.length > 0 && body[0] == (byte) 0x89 ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
  }
  return builder.contentType(type).body(body);
 }
 /** 读取指定窗口的实现，由调用方决定具体资源。 */
 @FunctionalInterface
 private interface SliceReader {
  /** 读取 [start, start+length) 的字节。 */
  byte[] read(long start, int length) throws Exception;
 }
 private ResponseEntity<byte[]> binary(byte[] value, MediaType declared) {
  MediaType type = declared;
  if (type == null) {
   type = value.length > 0 && value[0] == (byte) 0x89 ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
  }
  return ResponseEntity.ok().header("Cache-Control", "no-store")
   .header("X-Content-Type-Options", "nosniff").contentType(type).body(value);
 }
 private void authorize(String supplied, long owner) {
  String token = env.getProperty("video.token", "");
  if (owner <= 0 || token.isBlank() || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
   supplied.getBytes(StandardCharsets.UTF_8))) {
   throw new VideoException(ErrorCode.VIDEO_007);
  }
 }
 /** 将业务错误转换为稳定响应。 */
 @ExceptionHandler(VideoException.class)
 public ResponseEntity<Object> error(VideoException e) {
  int status = switch (e.code()) {
   case VIDEO_007 -> 401;
   case VIDEO_003, VIDEO_006 -> 503;
   case VIDEO_008 -> 409;
   case VIDEO_009 -> 404;
   case VIDEO_005 -> 504;
   // 能力不支持必须与输入无效区分：网关按状态码还原业务码，两者混在一起会让 App 显示错误的提示。
   case VIDEO_002 -> 422;
   default -> 400;
  };
  return ResponseEntity.status(status).body(Map.of("code", e.code().name(), "message", e.code().name()));
 }
 /** 参数错误使用稳定错误码，不返回字段内容。 */
 @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
  org.springframework.http.converter.HttpMessageNotReadableException.class})
 public ResponseEntity<Object> invalid(Exception e) {
  return ResponseEntity.badRequest().body(Map.of("code", ErrorCode.VIDEO_001.name(),
   "message", ErrorCode.VIDEO_001.name()));
 }
 /** 内部异常不向客户端暴露存储路径或上游响应。 */
 @ExceptionHandler(Exception.class)
 public ResponseEntity<Object> unavailable(Exception e) {
  org.slf4j.LoggerFactory.getLogger(VideoController.class).warn("Video request failed: {}", e.getClass().getSimpleName());
  return ResponseEntity.status(503).body(Map.of("code", ErrorCode.VIDEO_006.name(),
   "message", ErrorCode.VIDEO_006.name()));
 }
}
