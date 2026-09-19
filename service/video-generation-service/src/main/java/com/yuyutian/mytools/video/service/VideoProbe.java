package com.yuyutian.mytools.video.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.video.common.ErrorCode;
import com.yuyutian.mytools.video.common.VideoException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
/** 用固定 ffprobe 读取素材真实元数据；路径只能由服务端生成。 */
@Component
public class VideoProbe {
 /** 探测结果：由容器实测，不采用客户端申报值。 */
 public record Probe(long durationMs, int width, int height, String codec, boolean hasAudio) { }
 private static final long TIMEOUT_SECONDS = 20;
 private final String executable;
 private final ObjectMapper json;
 /** 注入固定可执行文件与 JSON 解析器。 */
 public VideoProbe(Environment env, ObjectMapper json) {
  this.executable = env.getProperty("video.ffprobe", "ffprobe");
  this.json = json;
 }
 /** 探测视频容器；解析失败或超时一律按输入无效处理。 */
 public Probe probe(Path path) {
  String output = run(path);
  try {
   JsonNode root = json.readTree(output);
   JsonNode stream = root.path("streams").path(0);
   if (stream.isMissingNode() || stream.path("width").asInt(0) <= 0) throw new VideoException(ErrorCode.VIDEO_001);
   long durationMs = Math.round(root.path("format").path("duration").asDouble(0) * 1000);
   boolean hasAudio = false;
   for (JsonNode item : root.path("streams")) {
    if (item.path("codec_type").asText().equals("audio")) hasAudio = true;
   }
   return new Probe(durationMs, stream.path("width").asInt(), stream.path("height").asInt(),
    stream.path("codec_name").asText(""), hasAudio);
  } catch (IOException e) {
   throw new VideoException(ErrorCode.VIDEO_001);
  }
 }
 private String run(Path path) {
  ProcessBuilder builder = new ProcessBuilder(executable, "-v", "error", "-show_streams", "-show_format",
   "-of", "json", path.toAbsolutePath().toString());
  // 只读探测：不继承服务进程环境，避免把凭据暴露给子进程。
  builder.environment().clear();
  builder.redirectErrorStream(false);
  try {
   Process process = builder.start();
   String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
   if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    process.destroyForcibly();
    throw new VideoException(ErrorCode.VIDEO_001);
   }
   if (process.exitValue() != 0 || output.isBlank()) throw new VideoException(ErrorCode.VIDEO_001);
   return output;
  } catch (IOException e) {
   throw new VideoException(ErrorCode.VIDEO_001);
  } catch (InterruptedException e) {
   Thread.currentThread().interrupt();
   throw new VideoException(ErrorCode.VIDEO_001);
  }
 }
}
