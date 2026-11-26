package com.yuyutian.mytools.pdf.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.pdf.common.*;
import com.yuyutian.mytools.pdf.service.*;
import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** PDF 内部领域接口，账户来源仅由可信网关提供。 */
@RestController
@RequestMapping("/internal/v1/pdf")
public class PdfController {
 private final PdfService service; private final PdfUpstream upstream; private final Environment env;
 /** 注入领域服务和固定上游。 */
 public PdfController(PdfService service,PdfUpstream upstream,Environment env){this.service=service;this.upstream=upstream;this.env=env;}
 /** 返回可用工具。 */
 @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request){owner(request);return service.capabilities();}
 /** 创建上传会话。 */
 @PostMapping("/uploads") public Object upload(HttpServletRequest request,@RequestBody JsonNode body){return service.upload(owner(request),body,key(request));}
 /** 接收流式内容或固定偏移分块。 */
 @PutMapping("/uploads/{id}/content") public Object content(HttpServletRequest request,@PathVariable UUID id,@RequestHeader(value="X-Upload-Offset",defaultValue="0") long offset)throws IOException{return service.content(owner(request),id.toString(),offset,request.getInputStream());}
 /** 封存输入并异步检查。 */
 @PostMapping("/uploads/{id}/complete") public Object complete(HttpServletRequest request,@PathVariable UUID id){return service.complete(owner(request),id.toString());}
 /** 返回已检查输入的状态。 */
 @GetMapping("/inputs/{id}") public Object input(HttpServletRequest request,@PathVariable UUID id){return service.get(owner(request),id.toString(),"INPUT");}
 /** 创建冻结的类型化计划。 */
 @PostMapping("/plans") public Object plan(HttpServletRequest request,@RequestBody JsonNode body){return service.plan(owner(request),body,key(request));}
 /** 读取计划生成状态。 */
 @GetMapping("/plans/{id}") public Object planStatus(HttpServletRequest request,@PathVariable UUID id){return service.get(owner(request),id.toString(),"PLAN");}
 /** 取消正在生成的计划。 */
 @PostMapping("/plans/{id}/cancel") public Object cancelPlan(HttpServletRequest request,@PathVariable UUID id){return service.cancel(owner(request),id.toString(),"PLAN");}
 /** 发布计划结果为用户任务。 */
 @PostMapping("/jobs") public Object start(HttpServletRequest request,@RequestBody JsonNode body){return service.start(owner(request),body,key(request));}
 /** 查询本人任务。 */
 @GetMapping("/jobs/{id}") public Object get(HttpServletRequest request,@PathVariable UUID id){return service.get(owner(request),id.toString(),"JOB");}
 /** 取消尚未完成的任务。 */
 @PostMapping("/jobs/{id}/cancel") public Object cancel(HttpServletRequest request,@PathVariable UUID id){return service.cancel(owner(request),id.toString(),"JOB");}
 /** 分页读取历史。 */
 @GetMapping({"/jobs","/history"}) public Object list(HttpServletRequest request,@RequestParam(defaultValue="0") int page){return service.list(owner(request),page,request.getRequestURI().endsWith("/history"));}
 /** 读取输入首屏缩略图。 */
 @GetMapping("/inputs/{id}/thumbnail") public void inputThumbnail(HttpServletRequest request,HttpServletResponse response,@PathVariable UUID id)throws IOException{stream(request,response,id,"INPUT",0,true);}
 /** 读取冻结输出缩略图。 */
 @GetMapping("/plans/{id}/pages/{index}/thumbnail") public void preview(HttpServletRequest request,HttpServletResponse response,@PathVariable UUID id,@PathVariable int index)throws IOException{stream(request,response,id,"PLAN",index,true);}
 /** 下载当前用户已发布的产物。 */
 @GetMapping("/jobs/{id}/artifacts/{index}/content") public void artifact(HttpServletRequest request,HttpServletResponse response,@PathVariable UUID id,@PathVariable int index)throws IOException{stream(request,response,id,"JOB",index,false);}
 /** Worker 领取冻结工作和执行栅栏。 */
 @PostMapping("/work/{id}/claim") public Object claim(HttpServletRequest request,@PathVariable UUID id){worker(request);return service.claim(id.toString(),task(request),fence(request));}
 /** Worker 读取当前工作授权的文件流。 */
 @GetMapping("/work/{id}/inputs/{input}/content") public void workerContent(HttpServletRequest request,HttpServletResponse response,@PathVariable UUID id,@PathVariable UUID input)throws IOException{worker(request);var path=service.workerInput(id.toString(),input.toString(),task(request),fence(request));response.setContentLengthLong(Files.size(path));Files.copy(path,response.getOutputStream());}
 /** Worker 提交原子处理结果。 */
 @PostMapping("/work/{id}/complete") public Object finish(HttpServletRequest request,@PathVariable UUID id,@RequestBody JsonNode body){worker(request);return service.finish(id.toString(),task(request),fence(request),body);}
 private void stream(HttpServletRequest request,HttpServletResponse response,UUID id,String kind,int index,boolean preview)throws IOException{var a=service.artifact(owner(request),id.toString(),kind,index,preview);response.setContentType(a.path("mediaType").asText());response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setContentLengthLong(a.path("bytes").asLong());if(!preview)response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(a.path("filename").asText(),StandardCharsets.UTF_8).build().toString());upstream.content(a.path("storageUri").asText(),response.getOutputStream(),a.path("bytes").asLong());}
 private long owner(HttpServletRequest r){auth(r,"pdf.token");try{long owner=Long.parseLong(r.getHeader("X-Owner-Id"));if(owner<=0)throw new NumberFormatException();return owner;}catch(Exception e){throw new PdfException(ErrorCode.PDF_014);}}
 private void worker(HttpServletRequest r){auth(r,"pdf.worker-token");}
 private void auth(HttpServletRequest r,String key){String token=env.getProperty(key,"");if(token.isBlank()||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),Objects.toString(r.getHeader("Authorization"),"").getBytes(StandardCharsets.UTF_8)))throw new PdfException(ErrorCode.PDF_014);}
 private String key(HttpServletRequest r){return r.getHeader("Idempotency-Key");}
 private String task(HttpServletRequest r){return PdfService.uuid(r.getHeader("X-Task-Instance-Id"));}
 private long fence(HttpServletRequest r){try{return Long.parseLong(r.getHeader("X-Task-Fencing-Token"));}catch(Exception e){throw new PdfException(ErrorCode.PDF_008);}}
 /** 使用稳定错误码而不是异常正文。 */
 @ExceptionHandler(PdfException.class) public ResponseEntity<?> error(PdfException e){return ResponseEntity.status(e.code().status()).body(Map.of("code",e.code().name(),"message",e.code().name()));}
 /** JSON 和路径校验异常不返回请求内容。 */
 @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) public ResponseEntity<?> invalid(Exception ignored){return ResponseEntity.unprocessableEntity().body(Map.of("code","PDF_001"));}
 /** 屏蔽内部文件路径和服务凭据。 */
 @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception ignored){return ResponseEntity.status(503).body(Map.of("code","PDF_009"));}
}
