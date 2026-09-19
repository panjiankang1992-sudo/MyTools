package com.yuyutian.mytools.video.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.video.common.ErrorCode;
import com.yuyutian.mytools.video.common.VideoException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
/** 固定管理端点的内部调用，不接受用户提供 URL 或密钥。 */
@Component
public class VideoUpstream {
 private final Environment env;
 private final RestTemplate http;
 /** 视频任务比图片长，读超时按调度器轮询的粒度设置。 */
 public VideoUpstream(Environment env) {
  this.env = env;
  var factory = new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(3000);
  factory.setReadTimeout(15000);
  this.http = new RestTemplate(factory);
 }
 /** 调用视频业务身份的调度接口。 */
 public JsonNode scheduler(String path, HttpMethod method, Object body) {
  HttpHeaders headers = new HttpHeaders();
  headers.set("X-Task-Service-Id", "video-generation-service");
  headers.set("X-Task-Business-Token", required("video.scheduler-token"));
  return send(required("video.scheduler-url") + path, method, body, headers);
 }
 /** 幂等登记生成的视频与封面资产。 */
 public JsonNode asset(Object body) {
  HttpHeaders headers = new HttpHeaders();
  headers.setBearerAuth(required("video.asset-token"));
  return send(required("video.asset-url") + "/internal/v1/assets", HttpMethod.POST, body, headers);
 }
 private JsonNode send(String url, HttpMethod method, Object body, HttpHeaders headers) {
  headers.setContentType(MediaType.APPLICATION_JSON);
  JsonNode result = http.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
  if (result == null) throw new VideoException(ErrorCode.VIDEO_006);
  return result;
 }
 private String required(String key) {
  String value = env.getProperty(key, "");
  if (value.isBlank()) throw new VideoException(ErrorCode.VIDEO_003);
  return value;
 }
}
