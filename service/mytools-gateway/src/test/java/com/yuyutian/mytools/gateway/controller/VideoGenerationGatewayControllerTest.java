package com.yuyutian.mytools.gateway.controller;
import com.yuyutian.mytools.gateway.common.ErrorCode;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.GatewayNotFoundException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.VideoPlaybackTicketService;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.gateway.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
/** 视频生成网关的固定上游、可信所有者与二进制透传契约测试。 */
class VideoGenerationGatewayControllerTest {
 private static final UUID JOB_ID=UUID.fromString("00000000-0000-0000-0000-000000000001");
 private MockEnvironment env(){return new MockEnvironment().withProperty("VIDEO_GENERATION_ROUTE_ENABLED","true").withProperty("VIDEO_GENERATION_INTERNAL_TOKEN","test-token").withProperty("VIDEO_GENERATION_URL","http://video");}
 private MockHttpServletRequest request(){return request(-1L);}
 private MockHttpServletRequest request(long contentLength){var r=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return contentLength;}};r.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID()));r.addHeader("X-Owner-Id","999");return r;}
 private RestTemplate client(VideoGenerationGatewayController controller){return (RestTemplate)Objects.requireNonNull(ReflectionTestUtils.getField(controller,"client"));}
 private VideoGenerationGatewayController controller(){return new VideoGenerationGatewayController(env(),new VideoPlaybackTicketService());}
 private VideoGenerationGatewayController controller(MockEnvironment environment,VideoPlaybackTicketService tickets){return new VideoGenerationGatewayController(environment,tickets);}
 private static String code(ResponseEntity<?> response){return (String)((Map<?,?>)response.getBody()).get("code");}
 @Test void refusesMissingTrustedPrincipal(){assertThrows(GatewayUnauthorizedException.class,()->controller().models(new MockHttpServletRequest()));}
 @Test void routeDisabledReturns503WithoutUpstream(){var disabled=env().withProperty("VIDEO_GENERATION_ROUTE_ENABLED","false");var response=new VideoGenerationGatewayController(disabled,new VideoPlaybackTicketService()).models(request());assertEquals(503,response.getStatusCode().value());assertEquals(ErrorCode.VIDEO_010.name(),code(response));}
 @Test void blankInternalTokenReturns503(){var blank=env().withProperty("VIDEO_GENERATION_INTERNAL_TOKEN","");var response=new VideoGenerationGatewayController(blank,new VideoPlaybackTicketService()).models(request());assertEquals(503,response.getStatusCode().value());assertEquals(ErrorCode.VIDEO_010.name(),code(response));}
 @Test void modelsForwardsFixedUpstreamAndTrustedOwner()throws Exception {
  var controller=controller();
  var server=MockRestServiceServer.bindTo(client(controller)).build();
  server.expect(requestTo("http://video/internal/v1/videos/models")).andExpect(method(HttpMethod.GET))
   .andExpect(header("Authorization","Bearer test-token")).andExpect(header("X-Owner-Id","42"))
   .andRespond(withSuccess("[{\"mode\":\"wan\"}]",MediaType.APPLICATION_JSON));
  MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build()
   .perform(get("/api/app/v1/video-generation/models").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())).header("X-Owner-Id","999"))
   .andExpect(MockMvcResultMatchers.status().isOk()).andExpect(MockMvcResultMatchers.jsonPath("$.code").value("0000"))
   .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].mode").value("wan"));
  server.verify();
 }
 @Test void uploadImageForwardsRawBytesAndRequestContentType()throws Exception {
  var controller=controller();byte[] bytes=new byte[]{(byte)0x89,80,78,71,13,10,26,10};
  var server=MockRestServiceServer.bindTo(client(controller)).build();
  server.expect(requestTo("http://video/internal/v1/videos/uploads/image")).andExpect(method(HttpMethod.POST))
   .andExpect(header("Content-Type","image/png")).andExpect(header("Authorization","Bearer test-token"))
   .andExpect(header("X-Owner-Id","42")).andExpect(content().bytes(bytes))
   .andRespond(withSuccess("{\"id\":\"a\"}",MediaType.APPLICATION_JSON));
  MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build()
   .perform(post("/api/app/v1/video-generation/uploads/image").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())).contentType(MediaType.IMAGE_PNG).content(bytes))
   .andExpect(MockMvcResultMatchers.status().isOk());
  server.verify();
 }
 @Test void uploadVideoForcesMp4ContentType()throws Exception {
  var controller=controller();byte[] bytes=new byte[]{0,0,0,24,102,116,121,112};
  var server=MockRestServiceServer.bindTo(client(controller)).build();
  server.expect(requestTo("http://video/internal/v1/videos/uploads/video")).andExpect(method(HttpMethod.POST))
   .andExpect(header("Content-Type","video/mp4")).andExpect(content().bytes(bytes))
   .andRespond(withSuccess("{\"id\":\"b\"}",MediaType.APPLICATION_JSON));
  MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build()
   .perform(post("/api/app/v1/video-generation/uploads/video").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes))
   .andExpect(MockMvcResultMatchers.status().isOk());
  server.verify();
 }
 @Test void oversizedUploadReturns413WithoutUpstreamCall(){
  var controller=controller();
  var server=MockRestServiceServer.bindTo(client(controller)).build();
  var imageResponse=controller.uploadImage(request(VideoGenerationGatewayController.MAX_IMAGE_UPLOAD_BYTES+1),new byte[]{1});
  assertEquals(413,imageResponse.getStatusCode().value());assertEquals(ErrorCode.VIDEO_001.name(),code(imageResponse));
  var videoResponse=controller.uploadVideo(request(VideoGenerationGatewayController.MAX_VIDEO_UPLOAD_BYTES+1),new byte[]{1});
  assertEquals(413,videoResponse.getStatusCode().value());assertEquals(ErrorCode.VIDEO_001.name(),code(videoResponse));
  server.verify();
 }
 @Test void upstreamNotFoundPassesThrough404(){
  var controller=controller();
  var server=MockRestServiceServer.bindTo(client(controller)).build();
  server.expect(requestTo("http://video/internal/v1/videos/jobs/"+JOB_ID)).andRespond(withStatus(HttpStatus.NOT_FOUND));
  var response=controller.get(request(),JOB_ID);
  assertEquals(404,response.getStatusCode().value());assertEquals(ErrorCode.VIDEO_009.name(),code(response));
  server.verify();
 }
 @Test void upstreamServerErrorBecomes503(){
  var controller=controller();
  var server=MockRestServiceServer.bindTo(client(controller)).build();
  server.expect(requestTo("http://video/internal/v1/videos/works?page=0")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
  var response=controller.works(request(),0);
  assertEquals(503,response.getStatusCode().value());assertEquals(ErrorCode.VIDEO_010.name(),code(response));
  server.verify();
 }
 @Test void streamForwardsFullBodyAndContentType()throws Exception {
  // 上游用真实 HTTP 服务：流式端点是 HttpURLConnection，MockRestServiceServer 拦不到它。
  try(var upstream=new FakeVideoUpstream(new byte[]{0,0,0,24,102,116,121,112})){
   var controller=controller(env().withProperty("VIDEO_GENERATION_URL",upstream.base()),new VideoPlaybackTicketService());
   var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build();
   mvc.perform(get("/api/app/v1/video-generation/jobs/"+JOB_ID+"/video")
     .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())))
    .andExpect(MockMvcResultMatchers.status().isOk())
    .andExpect(MockMvcResultMatchers.content().contentType(MediaType.valueOf("video/mp4")))
    .andExpect(MockMvcResultMatchers.content().bytes(new byte[]{0,0,0,24,102,116,121,112}))
    .andExpect(MockMvcResultMatchers.header().string("Accept-Ranges","bytes"))
    .andExpect(MockMvcResultMatchers.header().string("Cache-Control","no-store"))
    .andExpect(MockMvcResultMatchers.header().string("X-Content-Type-Options","nosniff"));
   // 所有者与令牌由网关决定，客户端伪造的 X-Owner-Id 不起作用。
   assertEquals("42",upstream.lastOwner());
   assertEquals("Bearer test-token",upstream.lastAuthorization());
  }
 }

 @Test void streamForwardsRangeAndPassesThrough206()throws Exception {
  try(var upstream=new FakeVideoUpstream(new byte[]{10,11,12,13,14,15})){
   var controller=controller(env().withProperty("VIDEO_GENERATION_URL",upstream.base()),new VideoPlaybackTicketService());
   var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build();
   mvc.perform(get("/api/app/v1/video-generation/jobs/"+JOB_ID+"/video").header("Range","bytes=1-3")
     .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())))
    .andExpect(MockMvcResultMatchers.status().isPartialContent())
    .andExpect(MockMvcResultMatchers.content().bytes(new byte[]{11,12,13}))
    .andExpect(MockMvcResultMatchers.header().string("Content-Range","bytes 1-3/6"))
    .andExpect(MockMvcResultMatchers.header().string("Accept-Ranges","bytes"));
   assertEquals("bytes=1-3",upstream.lastRange());
  }
 }

 @Test void streamKeepsUnsatisfiableRangeAs416()throws Exception {
  try(var upstream=new FakeVideoUpstream(new byte[]{1,2,3})){
   var controller=controller(env().withProperty("VIDEO_GENERATION_URL",upstream.base()),new VideoPlaybackTicketService());
   var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build();
   mvc.perform(get("/api/app/v1/video-generation/jobs/"+JOB_ID+"/video").header("Range","bytes=99-")
     .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())))
    .andExpect(MockMvcResultMatchers.status().isRequestedRangeNotSatisfiable())
    .andExpect(MockMvcResultMatchers.header().string("Content-Range","bytes */3"));
  }
 }

 @Test void streamWithoutRouteOrTokenReturns503()throws Exception {
  try(var upstream=new FakeVideoUpstream(new byte[]{1,2,3})){
   var disabled=controller(env().withProperty("VIDEO_GENERATION_URL",upstream.base())
     .withProperty("VIDEO_GENERATION_ROUTE_ENABLED","false"),new VideoPlaybackTicketService());
   var mvc=MockMvcBuilders.standaloneSetup(disabled).setControllerAdvice(new GatewayEnvelopeAdvice()).build();
   mvc.perform(get("/api/app/v1/video-generation/jobs/"+JOB_ID+"/video")
     .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())))
    .andExpect(MockMvcResultMatchers.status().isServiceUnavailable());
   assertEquals(0,upstream.calls());
  }
 }

 @Test void ticketIsBoundToOwnerAndResourceAndPlaysWithoutPrincipal()throws Exception {
  try(var upstream=new FakeVideoUpstream(new byte[]{7,8,9})){
   var tickets=new VideoPlaybackTicketService();
   var controller=controller(env().withProperty("VIDEO_GENERATION_URL",upstream.base()),tickets);
   var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build();
   var issued=mvc.perform(post("/api/app/v1/video-generation/jobs/"+JOB_ID+"/ticket")
     .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())))
    .andExpect(MockMvcResultMatchers.status().isOk()).andReturn();
   String body=issued.getResponse().getContentAsString();
   String token=body.replaceAll(".*\"token\":\"([a-f0-9]{32})\".*","$1");
   assertEquals(32,token.length());
   // 票据本身即凭据：没有登录态也能取流，但只能取到票据绑定的那一个资源。
   mvc.perform(get("/api/video-generation/tickets/"+token))
    .andExpect(MockMvcResultMatchers.status().isOk())
    .andExpect(MockMvcResultMatchers.content().bytes(new byte[]{7,8,9}));
   assertEquals("/internal/v1/videos/jobs/"+JOB_ID+"/video",upstream.lastPath());
   assertThrows(GatewayNotFoundException.class,()->tickets.require("0".repeat(32)));
   assertThrows(GatewayNotFoundException.class,()->tickets.require(null));
   // 票据绑定所有者：签发给 42 的票据解析出来就是 42，而不是请求里的任何字段。
   assertEquals(42,tickets.require(token).ownerId());
  }
 }

 @Test void documentedAliasPrefixHitsTheSameHandlers()throws Exception {
  var controller=controller();
  var server=MockRestServiceServer.bindTo(client(controller)).build();
  server.expect(requestTo("http://video/internal/v1/videos/models"))
   .andRespond(withSuccess("[]",MediaType.APPLICATION_JSON));
  MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GatewayEnvelopeAdvice()).build()
   .perform(get("/api/video-generation/models").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(42,"user",List.of(),UUID.randomUUID())))
   .andExpect(MockMvcResultMatchers.status().isOk());
  server.verify();
 }

 /** 最小真实 HTTP 上游：按收到的 Range 返回 200 或 206，并记录收到的请求头。 */
 private static final class FakeVideoUpstream implements AutoCloseable {
  private final HttpServer server;
  private final byte[] body;
  private final AtomicReference<String> range=new AtomicReference<>();
  private final AtomicReference<String> owner=new AtomicReference<>();
  private final AtomicReference<String> authorization=new AtomicReference<>();
  private final AtomicReference<String> path=new AtomicReference<>();
  private final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();

  FakeVideoUpstream(byte[] body)throws IOException{
   this.body=body;
   this.server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   this.server.createContext("/",exchange->{
    calls.incrementAndGet();
    range.set(exchange.getRequestHeaders().getFirst("Range"));
    owner.set(exchange.getRequestHeaders().getFirst("X-Owner-Id"));
    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    path.set(exchange.getRequestURI().getPath());
    byte[] payload=body;
    int status=200;
    String contentRange=null;
    String header=exchange.getRequestHeaders().getFirst("Range");
    if(header!=null&&header.startsWith("bytes=")){
     String[] parts=header.substring("bytes=".length()).split("-",-1);
     int start=Integer.parseInt(parts[0]);
     if(start>=body.length){
      status=416;
      payload=new byte[0];
      contentRange="bytes */"+body.length;
     }else{
      int end=parts.length>1&&!parts[1].isEmpty()?Math.min(Integer.parseInt(parts[1]),body.length-1):body.length-1;
      status=206;
      payload=Arrays.copyOfRange(body,start,end+1);
      contentRange="bytes "+start+"-"+end+"/"+body.length;
     }
    }
    exchange.getResponseHeaders().set("Content-Type","video/mp4");
    exchange.getResponseHeaders().set("Accept-Ranges","bytes");
    if(contentRange!=null)exchange.getResponseHeaders().set("Content-Range",contentRange);
    exchange.sendResponseHeaders(status,payload.length==0?-1:payload.length);
    if(payload.length>0)try(var out=exchange.getResponseBody()){out.write(payload);}
    exchange.close();
   });
   this.server.start();
  }
  String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
  String lastRange(){return range.get();}
  String lastOwner(){return owner.get();}
  String lastAuthorization(){return authorization.get();}
  String lastPath(){return path.get();}
  int calls(){return calls.get();}
  @Override public void close(){server.stop(0);}
 }
}
