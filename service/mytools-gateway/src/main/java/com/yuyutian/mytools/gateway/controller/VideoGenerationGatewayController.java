package com.yuyutian.mytools.gateway.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.gateway.common.ErrorCode;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.VideoPlaybackTicketService;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.*;
/** 视频生成只转发固定内部路径，所有者来自可信会话，上传保持原始字节。 */
@RestController
@RequestMapping({"/api/app/v1/video-generation", "/api/video-generation"})
public class VideoGenerationGatewayController {
 /** 图片素材允许的最大字节数。 */
 public static final long MAX_IMAGE_UPLOAD_BYTES=20L*1024*1024;
 /** 视频素材允许的最大字节数。 */
 public static final long MAX_VIDEO_UPLOAD_BYTES=200L*1024*1024;
 private static final String UPSTREAM_PREFIX="/internal/v1/videos";
 private static final String VIDEO_MP4="video/mp4";
 private final Environment env;
 private final RestTemplate client;
 private final VideoPlaybackTicketService tickets;
 /** 初始化固定内部上游及有界超时。 */
 public VideoGenerationGatewayController(Environment env,VideoPlaybackTicketService tickets){this.env=env;this.tickets=tickets;var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(15000);client=new RestTemplate(factory);}
 /** 读取逐模式的能力与验收状态。 */
 @GetMapping("/models") public ResponseEntity<?> models(HttpServletRequest request){return forward(request,"/models",HttpMethod.GET,null);}
 /** 转发原始字节图片素材，并保留请求声明的图片类型。 */
 @PostMapping(value="/uploads/image",consumes=MediaType.ALL_VALUE) public ResponseEntity<?> uploadImage(HttpServletRequest request,@RequestBody byte[] body){return upload(request,"image",body,MAX_IMAGE_UPLOAD_BYTES,mediaType(request.getContentType(),MediaType.APPLICATION_OCTET_STREAM));}
 /** 转发原始字节视频素材，上游固定声明 video/mp4。 */
 @PostMapping(value="/uploads/video",consumes=MediaType.ALL_VALUE) public ResponseEntity<?> uploadVideo(HttpServletRequest request,@RequestBody byte[] body){return upload(request,"video",body,MAX_VIDEO_UPLOAD_BYTES,MediaType.valueOf(VIDEO_MP4));}
 /** 幂等创建视频任务。 */
 @PostMapping("/jobs") public ResponseEntity<?> create(HttpServletRequest request,@RequestBody JsonNode body){return forward(request,"/jobs",HttpMethod.POST,body);}
 /** 读取本人任务。 */
 @GetMapping("/jobs/{id}") public ResponseEntity<?> get(HttpServletRequest request,@PathVariable UUID id){return forward(request,"/jobs/"+id,HttpMethod.GET,null);}
 /** 请求取消本人任务。 */
 @PostMapping("/jobs/{id}/cancel") public ResponseEntity<?> cancel(HttpServletRequest request,@PathVariable UUID id){return forward(request,"/jobs/"+id+"/cancel",HttpMethod.POST,Map.of());}
 /** 分页读取本人作品历史。 */
 @GetMapping("/works") public ResponseEntity<?> works(HttpServletRequest request,@RequestParam(defaultValue="0") int page){return forward(request,"/works?page="+page,HttpMethod.GET,null);}
 /** 读取本人上传素材，支持 Range，原片对比时可以只看片段。 */
 @GetMapping("/uploads/{id}") public void input(HttpServletRequest request,HttpServletResponse response,@PathVariable UUID id) throws IOException{streamWithPrincipal(request,response,"/uploads/"+id,MediaType.APPLICATION_OCTET_STREAM);}
 /** 流式下载本人成片：转发 Range，按 206 边下边播。 */
 @GetMapping("/jobs/{id}/video") public void video(HttpServletRequest request,HttpServletResponse response,@PathVariable UUID id) throws IOException{streamWithPrincipal(request,response,"/jobs/"+id+"/video",MediaType.valueOf(VIDEO_MP4));}
 /** 为成片签发播放票据：原生播放器无法带登录头，用票据 URL 取流。 */
 @PostMapping("/jobs/{id}/ticket") public ResponseEntity<?> videoTicket(HttpServletRequest request,@PathVariable UUID id){return issueTicket(request,VideoPlaybackTicketService.Kind.VIDEO,id);}
 /** 为原素材签发播放票据，供"原片对比"使用。 */
 @PostMapping("/uploads/{id}/ticket") public ResponseEntity<?> sourceTicket(HttpServletRequest request,@PathVariable UUID id){return issueTicket(request,VideoPlaybackTicketService.Kind.SOURCE,id);}
 /** 凭票据播放：不接受登录头，也不暴露内部令牌。 */
 @GetMapping("/tickets/{token}") public void playback(HttpServletRequest request,HttpServletResponse response,@PathVariable String token) throws IOException{
  VideoPlaybackTicketService.Ticket ticket=tickets.require(token);
  String path=ticket.kind()==VideoPlaybackTicketService.Kind.VIDEO?"/jobs/"+ticket.resourceId()+"/video":"/uploads/"+ticket.resourceId();
  stream(request,response,ticket.ownerId(),path,MediaType.valueOf(VIDEO_MP4));
 }
 /** 下载本人成片封面，原样透传上游类型与字节。 */
 @GetMapping("/jobs/{id}/cover") public ResponseEntity<?> cover(HttpServletRequest request,@PathVariable UUID id){return binary(request,"/jobs/"+id+"/cover",MediaType.IMAGE_PNG);}
 /** 转发 JSON 读写接口，客户端不能指定上游地址、服务令牌或所有者。 */
 private ResponseEntity<?> forward(HttpServletRequest request,String path,HttpMethod method,Object body){
  Verified verified=verify(request);
  if(verified==null)return failure(503,ErrorCode.VIDEO_010.name());
  try{
   return ResponseEntity.ok(client.exchange(verified.url()+path,method,new HttpEntity<>(body,verified.headers()),JsonNode.class).getBody());
  }catch(HttpStatusCodeException e){return upstream(e);}catch(RestClientException e){return transport(e);}
 }
 /** 转发原始字节上传，超限在读取前按 Content-Length 拒绝。 */
 private ResponseEntity<?> upload(HttpServletRequest request,String kind,byte[] body,long limit,MediaType type){
  Verified verified=verify(request);
  if(verified==null)return failure(503,ErrorCode.VIDEO_010.name());
  // 先按声明长度拦截，避免超大请求体进入上游；已缓冲的字节再做一次兜底判断。
  if(request.getContentLengthLong()>limit||body.length>limit)return failure(413,ErrorCode.VIDEO_001.name());
  HttpHeaders headers=new HttpHeaders();headers.putAll(verified.headers());headers.setContentType(type);
  try{
   return ResponseEntity.ok(client.exchange(verified.url()+"/uploads/"+kind,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class).getBody());
  }catch(HttpStatusCodeException e){return upstream(e);}catch(RestClientException e){return transport(e);}
 }
 /** 转发二进制读取接口（封面等小对象），保留上游类型并禁止缓存与嗅探。 */
 private ResponseEntity<?> binary(HttpServletRequest request,String path,MediaType fallback){
  Verified verified=verify(request);
  if(verified==null)return failure(503,ErrorCode.VIDEO_010.name());
  try{
   var reply=client.exchange(verified.url()+path,HttpMethod.GET,new HttpEntity<>(verified.headers()),byte[].class);
   MediaType type=reply.getHeaders().getContentType()==null?fallback:reply.getHeaders().getContentType();
   return ResponseEntity.ok().contentType(type).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(reply.getBody());
  }catch(HttpStatusCodeException e){return upstream(e);}catch(RestClientException e){return transport(e);}
 }
 /** 用已认证主体签发票据，票据只绑定"这个所有者的这个资源"。 */
 private ResponseEntity<?> issueTicket(HttpServletRequest request,VideoPlaybackTicketService.Kind kind,UUID id){
  if(!(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE) instanceof GatewayPrincipal principal))throw new GatewayUnauthorizedException();
  VideoPlaybackTicketService.Ticket ticket=tickets.issue(principal.userId(),kind,id);
  String prefix=env.getProperty("VIDEO_GENERATION_PUBLIC_PREFIX","/api/app/v1/video-generation");
  return ResponseEntity.ok(Map.of("token",ticket.token(),"url",prefix+"/tickets/"+ticket.token(),
   "expiresAt",ticket.expiresAt().toString(),"kind",kind.name()));
 }
 /** 从已认证请求解析上游信息后流式转发。 */
 private void streamWithPrincipal(HttpServletRequest request,HttpServletResponse response,String path,MediaType type) throws IOException{
  Verified verified=verify(request);
  if(verified==null){writeFailure(response,503,ErrorCode.VIDEO_010.name());return;}
  stream(request,response,ownerOf(verified),path,type);
 }
 /** 以固定内部令牌向上游取流，逐字节转发并原样保留 Range 相关响应头。 */
 private void stream(HttpServletRequest request,HttpServletResponse response,long owner,String path,MediaType fallback) throws IOException{
  if(env.getProperty("VIDEO_GENERATION_ROUTE_ENABLED",Boolean.class,false)!=true){writeFailure(response,503,ErrorCode.VIDEO_010.name());return;}
  String token=env.getProperty("VIDEO_GENERATION_INTERNAL_TOKEN","");
  if(token.isBlank()){writeFailure(response,503,ErrorCode.VIDEO_010.name());return;}
  HttpURLConnection connection=null;
  try{
   connection=(HttpURLConnection)URI.create(env.getProperty("VIDEO_GENERATION_URL","http://127.0.0.1:23341")+UPSTREAM_PREFIX+path).toURL().openConnection();
   connection.setRequestProperty("Authorization","Bearer "+token);
   connection.setRequestProperty("X-Owner-Id",Long.toString(owner));
   // 必须禁止压缩，否则 Content-Length 与 Range 偏移会与实体不一致。
   connection.setRequestProperty("Accept-Encoding","identity");
   String range=request.getHeader("Range");
   if(range!=null&&!range.isBlank())connection.setRequestProperty("Range",range);
   connection.setConnectTimeout(3000);
   connection.setReadTimeout(Math.max(15000,120000));
   int status=connection.getResponseCode();
   response.setStatus(status);
   copyHeader(connection,response,"Content-Type",fallback.toString());
   copyHeader(connection,response,"Content-Length",null);
   copyHeader(connection,response,"Content-Range",null);
   copyHeader(connection,response,"Accept-Ranges",null);
   response.setHeader("Cache-Control","no-store");
   response.setHeader("X-Content-Type-Options","nosniff");
   if(status<200||status>=300)return;
   try(var input=connection.getInputStream();var output=response.getOutputStream()){input.transferTo(output);}
  }catch(IOException e){
   writeFailure(response,503,ErrorCode.VIDEO_010.name());
  }finally{
   if(connection!=null)connection.disconnect();
  }
 }
 /** 复制上游响应头，缺失时使用给定默认值。 */
 private void copyHeader(HttpURLConnection connection,HttpServletResponse response,String name,String fallback){
  String value=connection.getHeaderField(name);
  if(value==null||value.isBlank())value=fallback;
  if(value!=null&&!value.isBlank())response.setHeader(name,value);
 }
 /** 流式路径无法再用 ResponseEntity，直接写稳定错误体。 */
 private void writeFailure(HttpServletResponse response,int status,String code) throws IOException{
  response.setStatus(status);
  response.setContentType(MediaType.APPLICATION_JSON_VALUE);
  response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + code + "\"}");
 }
 /** 已认证主体标识。 */
 private long ownerOf(Verified verified){return verified.ownerId();}
 /** 主体缺失即认证失败；路由关闭或令牌为空时返回 null 由调用方统一降级。 */
 private Verified verify(HttpServletRequest request){
  if(!(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE) instanceof GatewayPrincipal principal))throw new GatewayUnauthorizedException();
  String token=env.getProperty("VIDEO_GENERATION_INTERNAL_TOKEN","");
  if(!env.getProperty("VIDEO_GENERATION_ROUTE_ENABLED",Boolean.class,false)||token.isBlank())return null;
  HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);headers.set("X-Owner-Id",Long.toString(principal.userId()));
  return new Verified(env.getProperty("VIDEO_GENERATION_URL","http://127.0.0.1:23341")+UPSTREAM_PREFIX,headers,principal.userId());
 }
 /** 上游 4xx 原样透传状态码，5xx 统一降级为 503。 */
 private ResponseEntity<?> upstream(HttpStatusCodeException e){
  int status=e.getStatusCode().value();
  // 状态码与业务错误码一一对应；上游 503（资源不足或保存失败）与 5xx 对客户端都是"服务暂不可用"。
  if(status>=500)return failure(503,ErrorCode.VIDEO_010.name());
  String code=status==404?ErrorCode.VIDEO_009.name():status==409?ErrorCode.VIDEO_008.name()
   :status==400?ErrorCode.VIDEO_001.name():status==401?ErrorCode.VIDEO_007.name():ErrorCode.VIDEO_002.name();
  return failure(status,code);
 }
 /** 传输超时与不可达对客户端是同一件事，均不向前端暴露上游细节。 */
 private ResponseEntity<?> transport(RestClientException e){
  return failure(503,ErrorCode.VIDEO_010.name());
 }
 /** 解析请求声明的内容类型，缺失或非法时退回给定默认值。 */
 private MediaType mediaType(String supplied,MediaType fallback){
  if(supplied==null||supplied.isBlank())return fallback;
  try{return MediaType.parseMediaType(supplied);}catch(IllegalArgumentException e){return fallback;}
 }
 /** 生成网关既有错误体，不携带上游响应内容。 */
 private ResponseEntity<?> failure(int status,String code){return ResponseEntity.status(status).body(Map.of("code",code,"message",code));}
 /** 已校验的主体与固定上游信息。 */
 private record Verified(String url,HttpHeaders headers,long ownerId){}
}
