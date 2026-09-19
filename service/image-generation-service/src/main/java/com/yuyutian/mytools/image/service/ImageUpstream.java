package com.yuyutian.mytools.image.service;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import com.yuyutian.mytools.image.common.*;
/** 固定管理端点的内部调用，不接受用户提供 URL 或密钥。 */
@Component
public class ImageUpstream {
 private final Environment env;
 private final RestTemplate http;
 /** 设置内部请求时限。 */
 public ImageUpstream(Environment env) {
  this.env=env;var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(10000);
  this.http=new RestTemplate(factory);
 }
 /** 调用合法图片业务身份的调度接口。 */
 public JsonNode scheduler(String path,HttpMethod method,Object body) {
  HttpHeaders h=new HttpHeaders();h.set("X-Task-Service-Id","image-generation-service");
  h.set("X-Task-Business-Token",required("image.scheduler-token"));
  return send(required("image.scheduler-url")+path,method,body,h);
 }
 /** 幂等登记生成的图片资产。 */
 public JsonNode asset(Object body) {
  HttpHeaders h=new HttpHeaders();h.setBearerAuth(required("image.asset-token"));
  return send(required("image.asset-url")+"/internal/v1/assets",HttpMethod.POST,body,h);
 }
 private JsonNode send(String url,HttpMethod method,Object body,HttpHeaders headers) {
  headers.setContentType(MediaType.APPLICATION_JSON);
  var result=http.exchange(url,method,new HttpEntity<>(body,headers),JsonNode.class).getBody();
  if(result==null)throw new ImageException(ErrorCode.IMAGE_007);
  return result;
 }
 private String required(String key) {
  String value=env.getProperty(key,"");
  if(value.isBlank())throw new ImageException(ErrorCode.IMAGE_003);
  return value;
 }
}
