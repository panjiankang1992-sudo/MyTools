package com.yuyutian.mytools.image.controller;
import com.yuyutian.mytools.image.service.ImageService;
import com.yuyutian.mytools.image.model.ImageModels.*;
import com.yuyutian.mytools.image.common.*;
import jakarta.validation.Valid;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** 只接受网关认证与可信所有者的图片接口。 */
@RestController
@RequestMapping("/internal/v1/images")
public class ImageController {
 private final ImageService service;private final Environment env;
 /** 注入图片业务服务。 */
 public ImageController(ImageService service,Environment env){this.service=service;this.env=env;}
 /** 返回可用资源。 */
 @GetMapping("/models") public Object models(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner){authorize(auth,owner);return service.models();}
 /** 返回本人可用风格。 */
 @GetMapping("/styles") public Object styles(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner){authorize(auth,owner);return service.styles().list(owner);}
 /** 幂等新建本人风格。 */
 @PostMapping("/styles/{id}") public Object createStyle(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@PathVariable UUID id,@Valid @RequestBody StyleWrite body){authorize(auth,owner);return service.styles().create(owner,id,body);}
 /** 保存本人风格的新版本。 */
 @PutMapping("/styles/{id}") public Object updateStyle(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@PathVariable UUID id,@Valid @RequestBody StyleWrite body){authorize(auth,owner);return service.styles().update(owner,id,body);}
 /** 隐藏本人风格而保留历史版本。 */
 @DeleteMapping("/styles/{id}") public Object deleteStyle(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@PathVariable UUID id){authorize(auth,owner);service.styles().delete(owner,id);return Map.of("deleted",true);}
 /** 上传当前账户的底稿。 */
 @PostMapping("/uploads") public Object upload(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@Valid @RequestBody Upload body)throws Exception{authorize(auth,owner);return service.upload(owner,body);}
 /** 创建幂等任务。 */
 @PostMapping("/jobs") public Object create(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@Valid @RequestBody Create body){authorize(auth,owner);return service.create(owner,body);}
 /** 查询本人任务。 */
 @GetMapping("/jobs/{id}") public Object get(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@PathVariable UUID id){authorize(auth,owner);return service.get(owner,id.toString());}
 /** 请求取消本人任务。 */
 @PostMapping("/jobs/{id}/cancel") public Object cancel(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@PathVariable UUID id){authorize(auth,owner);return service.cancel(owner,id.toString());}
 /** 分页读取本人作品历史。 */
 @GetMapping("/works") public Object list(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@RequestParam(defaultValue="0") int page){authorize(auth,owner);return service.list(owner,page);}
 /** 读取本人原始素材。 */
 @GetMapping("/uploads/{id}") public ResponseEntity<byte[]> input(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@PathVariable UUID id)throws Exception{authorize(auth,owner);return binary(service.input(owner,id.toString()));}
 /** 将本人作品复制为新底稿，避免客户端重新编码原图。 */
 @PostMapping("/jobs/{id}/images/{index}/reference") public Object reference(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@PathVariable UUID id,@PathVariable int index)throws Exception{authorize(auth,owner);return service.upload(owner,new Upload(Base64.getEncoder().encodeToString(service.image(owner,id.toString(),index))));}
 /** 下载已持久化的本人图片。 */
 @GetMapping("/jobs/{id}/images/{index}") public ResponseEntity<byte[]> image(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Owner-Id",defaultValue="0") long owner,@PathVariable UUID id,@PathVariable int index)throws Exception{authorize(auth,owner);return binary(service.image(owner,id.toString(),index));}
 private ResponseEntity<byte[]> binary(byte[] value){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").contentType(value[0]==(byte)0x89?MediaType.IMAGE_PNG:MediaType.IMAGE_JPEG).body(value);}
 private void authorize(String supplied,long owner){String token=env.getProperty("image.token","");if(owner<=0||token.isBlank()||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ImageException(ErrorCode.IMAGE_001);}
 /** 将业务错误转换为稳定响应。 */
 @ExceptionHandler(ImageException.class) public ResponseEntity<Object> error(ImageException e){int status=switch(e.code()){case IMAGE_001->401;case IMAGE_003->503;case IMAGE_004->404;case IMAGE_005->409;default->400;};return ResponseEntity.status(status).body(Map.of("code",e.code().name(),"message",e.code().name()));}
 /** 参数错误使用稳定错误码，不返回字段内容。 */
 @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
 public ResponseEntity<Object> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("code",ErrorCode.IMAGE_002.name(),"message",ErrorCode.IMAGE_002.name()));}
 /** 内部异常不向客户端暴露存储路径或上游响应。 */
 @ExceptionHandler(Exception.class)
 public ResponseEntity<Object> unavailable(Exception e){org.slf4j.LoggerFactory.getLogger(ImageController.class).warn("Image request failed: {}",e.getClass().getSimpleName());return ResponseEntity.status(503).body(Map.of("code",ErrorCode.IMAGE_007.name(),"message",ErrorCode.IMAGE_007.name()));}
}
