package com.yuyutian.mytools.pdf.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.pdf.common.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
/** 固定内部服务地址的调度与存储访问，不接收客户端 URL。 */
@Component
public class PdfUpstream {
 private final Environment env;
 private final RestTemplate http;
 /** 设置连接与读取超时。 */
 public PdfUpstream(Environment env){this.env=env;var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(3000);f.setReadTimeout(60000);http=new RestTemplate(f);}
 /** 以独立业务身份调用调度器。 */
 public JsonNode scheduler(String path,HttpMethod method,Object body){HttpHeaders headers=new HttpHeaders();headers.set("X-Task-Service-Id",optional("pdf.scheduler-service-id","pdf-service"));headers.set("X-Task-Business-Token",required("pdf.scheduler-token"));headers.setContentType(MediaType.APPLICATION_JSON);return http.exchange(required("pdf.scheduler-url")+path,method,new HttpEntity<>(body,headers),JsonNode.class).getBody();}
 /** 将受管存储对象按有界流转发给当前已鉴权响应。 */
 public void content(String uri,OutputStream target,long expectedBytes) {
  URI value=URI.create(uri);if(!"storage".equals(value.getScheme())||value.getHost()==null||value.getQuery()!=null)throw new PdfException(ErrorCode.PDF_010);
  String path=value.getPath().substring(1);
  String url=required("pdf.storage-url")+"/api/internal/v1/storage/objects/content?rootName="+URLEncoder.encode(value.getHost(),StandardCharsets.UTF_8)+"&path="+URLEncoder.encode(path,StandardCharsets.UTF_8);
  http.execute(URI.create(url),HttpMethod.GET,request->request.getHeaders().setBearerAuth(required("pdf.storage-token")),response->{
   long total=0;byte[] buffer=new byte[65536];int count;
   while((count=response.getBody().read(buffer))!=-1){total+=count;if(total>expectedBytes)throw new IOException("PDF_010");target.write(buffer,0,count);}
   if(total!=expectedBytes)throw new IOException("PDF_010");return null;
  });
 }
 private String required(String key){String value=env.getProperty(key,"");if(value.isBlank())throw new PdfException(ErrorCode.PDF_009);return value;}
 /** 读取可覆盖的配置项，未配置时回退到私有实例的既有默认值。 */
 private String optional(String key,String fallback){String value=env.getProperty(key,"");return value.isBlank()?fallback:value;}
}
