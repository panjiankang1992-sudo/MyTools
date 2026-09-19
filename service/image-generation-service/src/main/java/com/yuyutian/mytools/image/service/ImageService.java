package com.yuyutian.mytools.image.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yuyutian.mytools.image.common.*;
import com.yuyutian.mytools.image.model.ImageModels.*;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
/** 图片任务、用户隔离、持久对账与受管文件读取。 */
@Service
public class ImageService {
 private final ImageStore store;
 private final ImageStyleService styles;
 private final ImageUpstream upstream;
 private final Environment env;
 private final Path root;
 private static final Set<String> TERMINAL=Set.of("SUCCEEDED","FAILED","CANCELLED","TIMED_OUT");
 /** 构造服务并初始化受管目录。 */
 public ImageService(ImageStore store,ImageUpstream upstream,Environment env) throws IOException {
  this(store,upstream,env,new ImageStyleService(store));
 }
 /** 注入共享风格目录与事务服务。 */
 @org.springframework.beans.factory.annotation.Autowired
 public ImageService(ImageStore store,ImageUpstream upstream,Environment env,ImageStyleService styles) throws IOException {
  this.store=store;this.styles=styles;this.upstream=upstream;this.env=env;
  root=Path.of(env.getProperty("image.root","./runtime/images")).toAbsolutePath().normalize();
  Files.createDirectories(root.resolve("inputs"));Files.createDirectories(root.resolve("outputs"));
 }
 /** 返回风格管理服务，复用图片服务的存储与账户边界。 */
 public ImageStyleService styles(){return styles;}
 /** 返回能力经过显式验收的模型目录。 */
 public List<Model> models() {
  boolean enabled=env.getProperty("image.local-validated",Boolean.class,false) && env.getProperty("image.gpu-coordination-validated",Boolean.class,false);
  boolean remote=env.getProperty("image.remote-validated",Boolean.class,false)&&!env.getProperty("image.remote-model","").isBlank();
  boolean style=enabled&&env.getProperty("image.style-validated",Boolean.class,false);
  boolean edit=enabled&&env.getProperty("image.edit-validated",Boolean.class,false);
  boolean vision=env.getProperty("image.prompt-validated",Boolean.class,false)&&env.getProperty("image.gpu-coordination-validated",Boolean.class,false);
  List<String> modes=new ArrayList<>(List.of("TEXT_TO_IMAGE"));
  // 分别开放已验收的参考和图生图工作流。
  if(style)modes.add("STYLE_REFERENCE");
  if(edit)modes.add("IMAGE_TO_IMAGE");
  return List.of(new Model("krea2-local","Krea 2 Turbo","LOCAL",enabled?"READY":"UNVERIFIED",
   modes,
   List.of("1024x1024","832x1216","1216x832"),List.of(1,2,4),style?2:edit?1:0,enabled?"":"LOCAL_VALIDATION_REQUIRED"),
   new Model("sillytraven-remote","SillyTraven","REMOTE",remote?"READY":"UNVERIFIED",List.of("TEXT_TO_IMAGE"),List.of("1024x1024"),List.of(1,2,4),0,remote?"":"PROVIDER_VALIDATION_REQUIRED"),
   new Model("vision-local","Image prompt extraction","LOCAL",vision?"READY":"UNVERIFIED",List.of("IMAGE_TO_PROMPT"),List.of("1024x1024"),List.of(1),1,vision?"":"VISION_VALIDATION_REQUIRED"));
 }
 /** 上传图片并检测尺寸与编码，不能提交服务器文件路径。 */
 public Uploaded upload(long owner,Upload request) throws IOException {
  byte[] bytes;
  try{bytes=Base64.getDecoder().decode(request.base64());}catch(IllegalArgumentException e){throw new ImageException(ErrorCode.IMAGE_002);}
  String mime=inspect(bytes);String id=UUID.randomUUID().toString();
  Files.write(root.resolve("inputs").resolve(id),bytes,StandardOpenOption.CREATE_NEW);
  store.jdbc().update("INSERT INTO image_upload(id,owner_id,mime_type,size_bytes,content_sha256) VALUES(?,?,?,?,?)",id,owner,mime,bytes.length,sha(bytes));
  return new Uploaded(UUID.fromString(id),mime,bytes.length);
 }
 /** 创建持久任务；幂等重试不受之后模型配置变化影响。 */
 public synchronized ObjectNode create(long owner,Create request) {
  ObjectNode submitted=store.json().valueToTree(request);
  // 省略新增空字段，保持旧客户端幂等摘要不变。
  for(String field:List.of("styleId","styleVersion","styleSourceJobId"))if(submitted.path(field).isNull())submitted.remove(field);
  String encoded=store.encode(submitted),hash=sha(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var existing=store.byKey(owner,request.idempotencyKey());
  if(!existing.isEmpty())return replay(existing.getFirst(),hash);
  Model model=models().stream().filter(m->m.id().equals(request.resourceId())).findFirst().orElseThrow(()->new ImageException(ErrorCode.IMAGE_002));
  if(!model.status().equals("READY"))throw new ImageException(ErrorCode.IMAGE_003);
  if(!model.modes().contains(request.mode())||!model.sizes().contains(request.size())||!model.counts().contains(request.count())
   ||request.references().size()>model.maxReferences()||new HashSet<>(request.references()).size()!=request.references().size()
   ||request.mode().equals("TEXT_TO_IMAGE")&&!request.references().isEmpty()
   ||request.mode().equals("STYLE_REFERENCE")&&request.references().isEmpty()
   ||Set.of("IMAGE_TO_IMAGE","IMAGE_TO_PROMPT").contains(request.mode())&&request.references().size()!=1)throw new ImageException(ErrorCode.IMAGE_002);
  for(UUID reference:request.references())ownedUpload(owner,reference.toString());
  ObjectNode snapshot=submitted.deepCopy();
  if(request.styleId()==null) {
   if(request.styleVersion()!=null||request.styleSourceJobId()!=null)throw new ImageException(ErrorCode.IMAGE_002);
  } else {
   if(request.styleId().isBlank()||request.styleVersion()==null||request.styleVersion()<1)throw new ImageException(ErrorCode.IMAGE_002);
   ObjectNode style;
   if(request.styleSourceJobId()!=null) {
    // 历史版本只来自当前账户已有任务，不能信任客户端传入的快照。
    JsonNode saved=parse(store.job(owner,request.styleSourceJobId().toString()).get("request_json")).path("styleSnapshot");
    if(!saved.isObject()||!saved.path("id").asText().equals(request.styleId())||saved.path("version").asInt()!=request.styleVersion())throw new ImageException(ErrorCode.IMAGE_004);
    style=(ObjectNode)saved.deepCopy();
   }else style=styles.resolve(owner,request.styleId(),request.styleVersion());
   snapshot.set("styleSnapshot",style);
   snapshot.put("effectivePrompt",styles.compose(style,request.prompt(),request.resourceId(),request.mode()));
  }
  snapshot.put("workflowRevision",request.resourceId().equals("krea2-local")?env.getProperty("image.workflow-revision","krea2-turbo-v1"):"remote-images-v1");
  // 图生图使用独立修订，避免误用文生图工作流忽略输入图片。
  if(request.mode().equals("IMAGE_TO_IMAGE"))snapshot.put("workflowRevision",env.getProperty("image.edit-workflow-revision","image-edit-v1"));
  if(request.mode().equals("IMAGE_TO_PROMPT"))snapshot.put("workflowRevision","image-prompt-v1");
  snapshot.put("modelId",request.resourceId().equals("krea2-local")?"krea2-turbo":env.getProperty("image.remote-model",""));
  if(request.mode().equals("IMAGE_TO_PROMPT"))snapshot.put("modelId",env.getProperty("image.vision-model","huihui_ai/qwen3-vl-abliterated:8b"));
  String id=UUID.randomUUID().toString();
  try{store.jdbc().update("INSERT INTO image_job(id,owner_id,idempotency_key,request_sha256,request_json,status) VALUES(?,?,?,?,?,?)",id,owner,request.idempotencyKey(),hash,store.encode(snapshot),"QUEUED");}
  catch(DuplicateKeyException e){return replay(store.byKey(owner,request.idempotencyKey()).getFirst(),hash);}
  return view(store.job(owner,id));
 }
 /** 查询用户任务。 */
 public ObjectNode get(long owner,String id){return view(store.job(owner,id));}
 /** 查询用户作品与任务历史。 */
 public List<ObjectNode> list(long owner,int page){if(page<0||page>10000)throw new ImageException(ErrorCode.IMAGE_002);return store.list(owner,page).stream().map(this::view).toList();}
 /** 取消请求持久化后由后台与调度器对账。 */
 public synchronized ObjectNode cancel(long owner,String id){
  store.job(owner,id);
  store.jdbc().update("UPDATE image_job SET status=CASE WHEN task_id IS NULL AND dispatch_started=FALSE THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')",id,owner);
  return get(owner,id);
 }
 /** 读取本人上传文件。 */
 public byte[] input(long owner,String id)throws IOException {ownedUpload(owner,id);return Files.readAllBytes(safe(root.resolve("inputs").resolve(id)));}
 /** 读取本人已登记的成品，输出路径不能由调用方控制。 */
 public byte[] image(long owner,String id,int index)throws IOException {
  JsonNode result=parse(store.job(owner,id).get("result_json"));
  boolean exists=false;
  for(JsonNode item:result.path("images"))if(item.path("index").asInt(-1)==index&&item.hasNonNull("assetId"))exists=true;
  if(!exists)throw new ImageException(ErrorCode.IMAGE_004);
  return Files.readAllBytes(safe(root.resolve("outputs").resolve(id).resolve(index+".png")));
 }
 /** 后台重复派发及结果对账采用稳定幂等键，可在服务重启后恢复。 */
 @Scheduled(fixedDelayString="${image.reconcile-ms:3000}")
 public void reconcile(){
  for(var row:store.pending()){
   try{advance(row);}catch(Exception e){
    // 保留待对账状态，避免短暂网络故障导致重复计费或丢失已生成图片。
    store.jdbc().update("UPDATE image_job SET error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",ErrorCode.IMAGE_007.name(),row.get("id"));
   }
  }
 }
 private void advance(Map<String,Object> row)throws IOException {
  String id=row.get("id").toString(),status=row.get("status").toString();long owner=((Number)row.get("owner_id")).longValue();
  String task=row.get("task_id")==null?null:row.get("task_id").toString();
  if(task==null){
   // 派发前持久化边界；尚未派发的取消可直接结束，响应丢失则继续幂等对账。
   int admitted=store.jdbc().update("UPDATE image_job SET dispatch_started=TRUE WHERE id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')",id);
   if(admitted==0)return;
   ObjectNode parameters=(ObjectNode)parse(row.get("request_json"));parameters.put("jobId",id);
   // 调度器继续使用已验收契约；图片服务独立保存原始描述与风格快照。
   if(parameters.has("effectivePrompt"))parameters.put("prompt",parameters.path("effectivePrompt").asText());
   parameters.remove(List.of("styleId","styleVersion","styleSourceJobId","styleSnapshot","effectivePrompt"));
   JsonNode created=upstream.scheduler("/api/v1/task-instances",HttpMethod.POST,Map.of("taskName","image_generate","idempotencyKey","image:"+id,"businessType","IMAGE_GENERATION","businessId",id,"priority",40,"parameters",parameters));
   task=UUID.fromString(created.path("id").asText()).toString();
   store.jdbc().update("UPDATE image_job SET task_id=?,error_code=NULL WHERE id=?",task,id);
  }
  status=store.job(owner,id).get("status").toString();
  if(status.equals("CANCEL_REQUESTED"))upstream.scheduler("/api/v1/task-instances/"+task+"/cancel",HttpMethod.POST,Map.of());
  JsonNode summary=upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null);
  String terminal=summary.path("status").asText();
  if(!TERMINAL.contains(terminal)){
   if(!status.equals("CANCEL_REQUESTED"))store.jdbc().update("UPDATE image_job SET status=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('QUEUED','RUNNING')",terminal.equals("RUNNING")?"RUNNING":"QUEUED",id);
   return;
  }
  // 反推结果只保存文本，不将其当作生成图片登记资产。
  if(parse(row.get("request_json")).path("mode").asText().equals("IMAGE_TO_PROMPT")){
   Path path=root.resolve("outputs").resolve(id).resolve("prompt.json");
   ObjectNode result=store.json().createObjectNode();
   String finalStatus=terminal.equals("CANCELLED")?"CANCELLED":"FAILED";
   if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){
    String prompt=parse(Files.readString(safe(path))).path("prompt").asText();
    // 无效模型输出直接终结为失败，避免永久停留在待对账列表。
    if(!prompt.isBlank()&&prompt.length()<=4000){result.put("prompt",prompt);finalStatus="SUCCEEDED";}
   }
   store.jdbc().update("UPDATE image_job SET status=?,result_json=?,error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",finalStatus,store.encode(result),finalStatus.equals("FAILED")?ErrorCode.IMAGE_008.name():null,id);
   return;
  }
  int count=parse(row.get("request_json")).path("count").asInt();ArrayNode images=store.json().createArrayNode();
  store.jdbc().update("UPDATE image_job SET status='PERSISTING' WHERE id=?",id);
  // 路径只由本服务签发的任务标识和有界序号组成；部分成功结果也需要登记。
  for(int index=0;index<count;index++){
   Path path=root.resolve("outputs").resolve(id).resolve(index+".png");
   if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
   byte[] bytes=Files.readAllBytes(safe(path));String mime=inspect(bytes);
   // 首张保留历史来源编号；后续图片使用独立编号，满足资产来源唯一约束。
   String sourceId=index==0?id:id+":"+index;
   JsonNode asset=upstream.asset(Map.of("ownerId",owner,"idempotencyKey","image:"+id+":"+index,"sourceType","IMAGE_GENERATION","sourceBusinessId",sourceId,"contentSha256",sha(bytes),"sizeBytes",bytes.length,"mimeType",mime,
    "location",Map.of("idempotencyKey","image-location:"+id+":"+index,"providerType","LOCAL","storageUri",path.toUri().toString(),"providerVersion","1")));
   String assetId=asset.path("id").asText();if(assetId.isBlank())throw new ImageException(ErrorCode.IMAGE_007);
   images.addObject().put("index",index).put("assetId",assetId).put("mimeType",mime).put("sizeBytes",bytes.length);
  }
  boolean unconfirmed=false;
  for(int index=0;index<count;index++){
   Path marker=root.resolve("outputs").resolve(id).resolve(index+".submission.json");
   if(Files.exists(marker)&&!Files.exists(root.resolve("outputs").resolve(id).resolve(index+".png"))){
    JsonNode submission=parse(Files.readString(safe(marker)));
    boolean remote=parse(row.get("request_json")).path("resourceId").asText().equals("sillytraven-remote");
    if(!submission.path("rejected").asBoolean()&&(remote||!submission.hasNonNull("promptId")))unconfirmed=true;
   }
  }
  String finalStatus=images.size()==count?"SUCCEEDED":!images.isEmpty()?"PARTIAL_SUCCESS":unconfirmed?"UNCONFIRMED":terminal.equals("CANCELLED")?"CANCELLED":"FAILED";
  ObjectNode saved=store.json().createObjectNode();saved.set("images",images);saved.put("unconfirmed",unconfirmed);
  saved.put("modelId",parse(row.get("request_json")).path("modelId").asText());saved.put("workflowRevision",parse(row.get("request_json")).path("workflowRevision").asText());
  store.jdbc().update("UPDATE image_job SET status=?,result_json=?,error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",finalStatus,store.encode(saved),unconfirmed?ErrorCode.IMAGE_009.name():finalStatus.equals("FAILED")?ErrorCode.IMAGE_008.name():null,id);
 }
 private ObjectNode replay(Map<String,Object> row,String hash){if(!hash.equals(row.get("request_sha256")))throw new ImageException(ErrorCode.IMAGE_005);return view(row);}
 private void ownedUpload(long owner,String id){if(store.jdbc().queryForList("SELECT id FROM image_upload WHERE id=? AND owner_id=?",id,owner).isEmpty())throw new ImageException(ErrorCode.IMAGE_004);}
 private ObjectNode view(Map<String,Object> row){
  ObjectNode node=store.json().createObjectNode();for(String key:List.of("id","status"))node.put(key,row.get(key).toString());
  node.put("createdAt",row.get("created_at").toString());node.put("errorCode",row.get("error_code")==null?"":row.get("error_code").toString());
  // 总耗时包含排队、生成和结果保存，终态使用持久化结束时间，刷新不会继续增长。
  node.put("elapsedMillis",Math.max(0,((Number)row.get("elapsed_millis")).longValue()));
  node.set("request",parse(row.get("request_json")));node.set("result",parse(row.get("result_json")));return node;
 }
 private JsonNode parse(Object value){try{return value==null?store.json().createObjectNode():store.json().readTree(value.toString());}catch(IOException e){throw new ImageException(ErrorCode.IMAGE_002);}}
 private Path safe(Path p)throws IOException {
  for(Path part=root;part!=null&&p.startsWith(part);){if(Files.isSymbolicLink(part))throw new ImageException(ErrorCode.IMAGE_006);if(part.equals(p))break;part=part.resolve(p.getName(part.getNameCount()));}
  if(!p.toRealPath().startsWith(root.toRealPath())||Files.size(p)>12*1024*1024)throw new ImageException(ErrorCode.IMAGE_006);return p;}
 private String inspect(byte[] bytes)throws IOException {
  if(bytes.length==0||bytes.length>5*1024*1024)throw new ImageException(ErrorCode.IMAGE_006);
  try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
   var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new ImageException(ErrorCode.IMAGE_006);
   var reader=readers.next();try{reader.setInput(stream);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
    if(!Set.of("png","jpeg","jpg").contains(format)||(long)reader.getWidth(0)*reader.getHeight(0)>16000000)throw new ImageException(ErrorCode.IMAGE_006);
    if(reader.read(0)==null)throw new ImageException(ErrorCode.IMAGE_006);
    return format.equals("png")?"image/png":"image/jpeg";
   }finally{reader.dispose();}
  }
 }
 private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
}
