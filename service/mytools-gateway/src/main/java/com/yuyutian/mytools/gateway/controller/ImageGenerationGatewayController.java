package com.yuyutian.mytools.gateway.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.gateway.common.ErrorCode;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
/** 图片工具只转发固定路径，并从可信会话生成所有者。 */
@RestController
@RequestMapping("/api/app/v1/image-generation")
public class ImageGenerationGatewayController {
 private final Environment env;
 private final RestTemplate client;
 /** 初始化固定内部上游及有界超时。 */
 public ImageGenerationGatewayController(Environment env){this.env=env;var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(15000);client=new RestTemplate(factory);}
 /** 读取模型能力。 */
 @GetMapping("/models") public ResponseEntity<?> models(HttpServletRequest request){return forward(request,"/models",HttpMethod.GET,null,false);}
 /** 返回认证账户的风格目录。 */
 @GetMapping("/styles") public ResponseEntity<?> styles(HttpServletRequest request){return forward(request,"/styles",HttpMethod.GET,null,false);}
 /** 幂等创建私有风格。 */
 @PostMapping("/styles/{id}") public ResponseEntity<?> createStyle(HttpServletRequest request,@PathVariable UUID id,@RequestBody JsonNode body){return forward(request,"/styles/"+id,HttpMethod.POST,body,false);}
 /** 更新私有风格版本。 */
 @PutMapping("/styles/{id}") public ResponseEntity<?> updateStyle(HttpServletRequest request,@PathVariable UUID id,@RequestBody JsonNode body){return forward(request,"/styles/"+id,HttpMethod.PUT,body,false);}
 /** 删除私有风格入口。 */
 @DeleteMapping("/styles/{id}") public ResponseEntity<?> deleteStyle(HttpServletRequest request,@PathVariable UUID id){return forward(request,"/styles/"+id,HttpMethod.DELETE,null,false);}
 /** 上传底稿。 */
 @PostMapping("/uploads") public ResponseEntity<?> upload(HttpServletRequest request,@RequestBody JsonNode body){return forward(request,"/uploads",HttpMethod.POST,body,false);}
 /** 创建任务。 */
 @PostMapping("/jobs") public ResponseEntity<?> create(HttpServletRequest request,@RequestBody JsonNode body){return forward(request,"/jobs",HttpMethod.POST,body,false);}
 /** 读取任务。 */
 @GetMapping("/jobs/{id}") public ResponseEntity<?> get(HttpServletRequest request,@PathVariable UUID id){return forward(request,"/jobs/"+id,HttpMethod.GET,null,false);}
 /** 请求取消任务。 */
 @PostMapping("/jobs/{id}/cancel") public ResponseEntity<?> cancel(HttpServletRequest request,@PathVariable UUID id){return forward(request,"/jobs/"+id+"/cancel",HttpMethod.POST,Map.of(),false);}
 /** 读取作品历史。 */
 @GetMapping("/works") public ResponseEntity<?> works(HttpServletRequest request,@RequestParam(defaultValue="0") int page){return forward(request,"/works?page="+page,HttpMethod.GET,null,false);}
 /** 读取本人底稿。 */
 @GetMapping("/uploads/{id}") public ResponseEntity<?> input(HttpServletRequest request,@PathVariable UUID id){return forward(request,"/uploads/"+id,HttpMethod.GET,null,true);}
 /** 将本人作品设为底稿。 */
 @PostMapping("/jobs/{id}/images/{index}/reference") public ResponseEntity<?> reference(HttpServletRequest request,@PathVariable UUID id,@PathVariable int index){return forward(request,"/jobs/"+id+"/images/"+index+"/reference",HttpMethod.POST,Map.of(),false);}
 /** 下载本人图片。 */
 @GetMapping("/jobs/{id}/images/{index}") public ResponseEntity<?> image(HttpServletRequest request,@PathVariable UUID id,@PathVariable int index){return forward(request,"/jobs/"+id+"/images/"+index,HttpMethod.GET,null,true);}
 private ResponseEntity<?> forward(HttpServletRequest request,String path,HttpMethod method,Object body,boolean binary){
  if(!(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE) instanceof GatewayPrincipal principal))throw new GatewayUnauthorizedException();
  String token=env.getProperty("IMAGE_GENERATION_INTERNAL_TOKEN","");
  if(!env.getProperty("IMAGE_GENERATION_ROUTE_ENABLED",Boolean.class,false)||token.isBlank())return failure(503,ErrorCode.IMAGE_003.name());
  HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);headers.set("X-Owner-Id",Long.toString(principal.userId()));headers.setContentType(MediaType.APPLICATION_JSON);
  String url=env.getProperty("IMAGE_GENERATION_URL","http://127.0.0.1:23340")+"/internal/v1/images"+path;
  try{
   // 客户端不能指定上游地址、服务令牌或资源所有者。
   if(binary){var reply=client.exchange(url,method,new HttpEntity<>(body,headers),byte[].class);return ResponseEntity.ok().contentType(reply.getHeaders().getContentType()==null?MediaType.APPLICATION_OCTET_STREAM:reply.getHeaders().getContentType()).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(reply.getBody());}
   return ResponseEntity.ok(client.exchange(url,method,new HttpEntity<>(body,headers),JsonNode.class).getBody());
  }catch(HttpStatusCodeException e){int status=e.getStatusCode().value();return failure(status>=400&&status<500?status:503,status==404?ErrorCode.IMAGE_004.name():status==409?ErrorCode.IMAGE_005.name():status>=500?ErrorCode.IMAGE_007.name():ErrorCode.IMAGE_002.name());}
   catch(RestClientException e){return failure(503,ErrorCode.IMAGE_007.name());}
 }
 private ResponseEntity<?> failure(int status,String code){return ResponseEntity.status(status).body(Map.of("code",code,"message",code));}
}
