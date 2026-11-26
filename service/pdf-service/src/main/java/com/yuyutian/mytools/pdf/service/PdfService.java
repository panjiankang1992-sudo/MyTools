package com.yuyutian.mytools.pdf.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yuyutian.mytools.pdf.common.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** PDF 输入、不可变预览计划、幂等任务与结果发布。 */
@Service
public class PdfService {
 private static final long MAX_INPUT=100L*1024*1024;
 private static final Set<String> TYPES=Set.of("application/pdf","image/png","image/jpeg","text/plain","text/markdown");
 private final PdfStore store;
 private final PdfUpstream upstream;
 private final Environment env;
 private final Path root;
 /** 初始化专属输入目录。 */
 public PdfService(PdfStore store,PdfUpstream upstream,Environment env)throws IOException{this.store=store;this.upstream=upstream;this.env=env;root=Path.of(env.getProperty("pdf.root","./runtime/pdf")).toAbsolutePath().normalize();Files.createDirectories(root);try{Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){/* 非 POSIX 平台由运行账户保护目录。 */}}
 /** 返回真正启用的工具目录。 */
 public ObjectNode capabilities(){ObjectNode result=store.json().createObjectNode();result.put("schemaVersion","1.0");result.put("maxInputBytes",MAX_INPUT);result.put("maxInputs",100);result.put("maxInputPages",500);result.put("maxOutputPages",1000);result.put("uploadChunkBytes",1048576);var operations=result.putArray("operations");if(env.getProperty("pdf.enabled",Boolean.class,false)){for(String op:PdfRules.OPERATIONS.stream().sorted().toList()){if(op.contains("TEXT_TO_PDF")||op.equals("MARKDOWN_TO_PDF")){if(!env.getProperty("pdf.text-enabled",Boolean.class,false))continue;}operations.add(op);}}return result;}
 /** 创建原始字节上传会话，使用客户端稳定幂等键。 */
 public ObjectNode upload(long owner,JsonNode body,String key){enabled();PdfRules.fields(body,"name","bytes","mediaType");PdfRules.name(body.path("name").asText());if(!body.path("bytes").isIntegralNumber()||body.path("bytes").asLong()<1||body.path("bytes").asLong()>MAX_INPUT)throw new PdfException(ErrorCode.PDF_003);if(!TYPES.contains(body.path("mediaType").asText()))throw new PdfException(ErrorCode.PDF_002);return store.locked(owner,()->{
  var replay=replay(owner,"INPUT",key,(ObjectNode)body);if(replay!=null)return replay;quota(owner);
  long reserved=0;for(var existing:store.jdbc().queryForList("SELECT payload_json FROM pdf_record WHERE owner_id=? AND kind='INPUT' AND status<>'EXPIRED'",owner))reserved+=store.object(existing.get("payload_json")).path("bytes").asLong();
  if(reserved+body.path("bytes").asLong()>500L*1024*1024)throw new PdfException(ErrorCode.PDF_016);
  String id=store.insert(owner,"INPUT","UPLOADING",(ObjectNode)body,key,86400);return get(owner,id,"INPUT");});}
 /** 接受可重放的有界分块，整流上传也可使用 offset=0。 */
 public ObjectNode content(long owner,String id,long offset,InputStream input)throws IOException{
  // 分块先写到随机临时文件，实际读取限制避免伪造 Content-Length。
  Path temp=Files.createTempFile(root,"upload-",".part");
  try {long size=0;try(OutputStream output=Files.newOutputStream(temp)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){size+=count;if(size>MAX_INPUT)throw new PdfException(ErrorCode.PDF_003);output.write(buffer,0,count);}}
   long incoming=size;
   return store.locked(owner,()->{var row=store.owned(owner,id,"INPUT");live(row);if(!"UPLOADING".equals(row.get("status")))throw new PdfException(ErrorCode.PDF_008);long expected=store.object(row.get("payload_json")).path("bytes").asLong();Path target=path(id);
    try {long current=Files.exists(target)?Files.size(target):0;if(offset<0||offset>current||incoming<1||offset+incoming>expected)throw new PdfException(ErrorCode.PDF_008);
     if(offset<current){if(offset+incoming>current)throw new PdfException(ErrorCode.PDF_008);try(var existing=new RandomAccessFile(target.toFile(),"r");var retry=Files.newInputStream(temp)){existing.seek(offset);for(long i=0;i<incoming;i++)if(existing.read()!=retry.read())throw new PdfException(ErrorCode.PDF_008);}}
     else {try(var out=new RandomAccessFile(target.toFile(),"rw")){out.seek(current);try(var chunk=Files.newInputStream(temp)){byte[] buffer=new byte[65536];int read;while((read=chunk.read(buffer))!=-1)out.write(buffer,0,read);}catch(IOException failure){out.setLength(current);throw failure;}}}
     return store.json().createObjectNode().put("inputId",id).put("receivedBytes",Files.size(target));
    }catch(IOException e){throw new PdfException(ErrorCode.PDF_009);}
   });
  } finally {Files.deleteIfExists(temp);}
 }
 /** 封存上传并排队检查；重复完成不会创建重复工作。 */
 public ObjectNode complete(long owner,String id){return store.locked(owner,()->{var row=store.owned(owner,id,"INPUT");live(row);if(!"UPLOADING".equals(row.get("status")))return view(row);try{ObjectNode payload=store.object(row.get("payload_json"));if(Files.size(path(id))!=payload.path("bytes").asLong())throw new PdfException(ErrorCode.PDF_008);payload.put("sha256",digest(path(id)));store.jdbc().update("UPDATE pdf_record SET status='QUEUED',payload_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",store.encode(payload),id);return get(owner,id,"INPUT");}catch(IOException e){throw new PdfException(ErrorCode.PDF_009);}});}
 /** 创建严格类型化计划，所有文件必须属于当前用户且检查完成。 */
 public ObjectNode plan(long owner,JsonNode body,String key){enabled();PdfRules.plan(body);String operation=body.path("operation").asText();boolean available=false;for(JsonNode op:capabilities().path("operations"))if(op.asText().equals(operation))available=true;if(!available)throw new PdfException(ErrorCode.PDF_009);
  return store.locked(owner,()->{var replay=replay(owner,"PLAN",key,(ObjectNode)body);if(replay!=null)return replay;quota(owner);
   Integer recent=store.jdbc().queryForObject("SELECT COUNT(*) FROM pdf_record WHERE owner_id=? AND kind='PLAN' AND created_at>?",Integer.class,owner,Timestamp.from(Instant.now().minusSeconds(86400)));if(recent!=null&&recent>=30)throw new PdfException(ErrorCode.PDF_016);long total=0;
   for(JsonNode id:body.path("inputIds")){var input=store.owned(owner,id.asText(),"INPUT");live(input);if(!"READY".equals(input.get("status")))throw new PdfException(ErrorCode.PDF_008);ObjectNode p=store.object(input.get("payload_json"));String type=p.path("mediaType").asText();boolean valid=operation.equals("IMAGES_TO_PDF")?type.startsWith("image/"):operation.endsWith("TEXT_TO_PDF")||operation.equals("MARKDOWN_TO_PDF")?type.startsWith("text/"):type.equals("application/pdf");if(!valid)throw new PdfException(ErrorCode.PDF_002);total+=p.path("bytes").asLong();store.jdbc().update("UPDATE pdf_record SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().plusSeconds(86400)),id.asText());}
   for(JsonNode id:body.path("attachmentIds")){var input=store.owned(owner,id.asText(),"INPUT");live(input);ObjectNode p=store.object(input.get("payload_json"));if(!"READY".equals(input.get("status"))||!p.path("mediaType").asText().startsWith("image/"))throw new PdfException(ErrorCode.PDF_002);total+=p.path("bytes").asLong();store.jdbc().update("UPDATE pdf_record SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().plusSeconds(86400)),id.asText());}
   if(total>MAX_INPUT)throw new PdfException(ErrorCode.PDF_003);
   String id=store.insert(owner,"PLAN","QUEUED",(ObjectNode)body,key,1800);return get(owner,id,"PLAN");});}
 /** 发布已经验证的计划产物，保留独立幂等任务记录。 */
 public ObjectNode start(long owner,JsonNode body,String key){PdfRules.fields(body,"planId");String planId=uuid(body.path("planId").asText());return store.locked(owner,()->{var replay=replay(owner,"JOB",key,(ObjectNode)body);if(replay!=null)return replay;var plan=store.owned(owner,planId,"PLAN");live(plan);if(!"READY".equals(plan.get("status")))throw new PdfException(ErrorCode.PDF_008);String id=store.insert(owner,"JOB","SUCCEEDED",(ObjectNode)body,key,86400);ObjectNode result=store.object(plan.get("result_json"));result.put("operation",store.object(plan.get("payload_json")).path("operation").asText());store.jdbc().update("UPDATE pdf_record SET result_json=? WHERE id=?",store.encode(result),id);store.jdbc().update("UPDATE pdf_record SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().plusSeconds(86400)),planId);return get(owner,id,"JOB");});}
 /** 获取领域对象，不暴露调度器和存储凭据。 */
 public ObjectNode get(long owner,String id,String kind){var row=store.owned(owner,uuid(id),kind);live(row);return view(row);}
 /** 分页列出当前用户的任务。 */
 public List<ObjectNode> list(long owner,int page,boolean includePlans){if(page<0||page>10000)throw new PdfException(ErrorCode.PDF_001);return store.jdbc().queryForList("SELECT * FROM pdf_record WHERE owner_id=? AND (kind='JOB' OR (?=TRUE AND kind='PLAN')) ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?",owner,includePlans,page*20).stream().map(this::view).toList();}
 /** 取消计划处理；已发布任务返回真实终态。 */
 public ObjectNode cancel(long owner,String id,String kind){return store.locked(owner,()->{var row=store.owned(owner,uuid(id),kind);if(Set.of("QUEUED","RUNNING").contains(row.get("status"))){store.jdbc().update("UPDATE pdf_record SET status=CASE WHEN dispatch_started=FALSE THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);}return get(owner,id,kind);});}
 /** 返回受保护缩略图或产物的内部存储元数据。 */
 public ObjectNode artifact(long owner,String id,String kind,int index,boolean preview){var row=store.owned(owner,uuid(id),kind);live(row);if(!Set.of("READY","SUCCEEDED").contains(row.get("status")))throw new PdfException(ErrorCode.PDF_008);JsonNode entries=store.object(row.get("result_json")).path(preview?"previews":"artifacts");if(index<0||index>=entries.size())throw new PdfException(ErrorCode.PDF_015);return (ObjectNode)entries.get(index);}
 /** 为 Worker 返回冻结请求，先登记单调执行栅栏。 */
 public ObjectNode claim(String id,String task,long fence){var row=store.work(uuid(id));long owner=((Number)row.get("owner_id")).longValue();return store.locked(owner,()->{var current=store.work(id);fence(current,task,fence);store.jdbc().update("UPDATE pdf_record SET fencing_token=?,status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=?",fence,id);ObjectNode request=store.object(current.get("payload_json"));request.put("workId",id);request.put("ownerId",Long.toString(owner));if(current.get("kind").equals("INPUT")){request.put("operation","INSPECT");request.putArray("inputIds").add(id);}else{var refs=request.putArray("resolvedInputs");for(JsonNode input:references(request)){var source=store.owned(owner,input.asText(),"INPUT");ObjectNode ref=store.object(source.get("payload_json"));ref.put("inputId",input.asText());refs.add(ref);}}return request;});}
 /** Worker 仅能读取当前工作明确引用的输入文件。 */
 public Path workerInput(String work,String input,String task,long token){var row=store.work(uuid(work));fence(row,task,token);ObjectNode payload=store.object(row.get("payload_json"));boolean allowed=row.get("kind").equals("INPUT")&&work.equals(input);for(JsonNode ref:references(payload))if(ref.asText().equals(input))allowed=true;if(!allowed)throw new PdfException(ErrorCode.PDF_015);return path(uuid(input));}
 /** 原子提交可信 Worker 的有界产物清单，取消和旧执行均不能覆盖终态。 */
 public ObjectNode finish(String id,String task,long token,JsonNode result){var row=store.work(uuid(id));long owner=((Number)row.get("owner_id")).longValue();return store.locked(owner,()->{var current=store.work(id);if("READY".equals(current.get("status"))&&task.equals(current.get("task_id"))&&token==((Number)current.get("fencing_token")).longValue())return view(current);fence(current,task,token);
   if(result.has("errorCode")){String code=result.path("errorCode").asText();try{ErrorCode.valueOf(code);}catch(Exception e){code="PDF_010";}store.jdbc().update("UPDATE pdf_record SET status='FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",code,id);}
   else{if(current.get("kind").equals("PLAN")&&(!result.path("artifacts").isArray()||result.path("artifacts").isEmpty()))throw new PdfException(ErrorCode.PDF_010);validateResult(id,result);store.jdbc().update("UPDATE pdf_record SET status='READY',result_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",store.encode(result),id);}
   return view(store.work(id));});}
 /** 对持久化队列和 Scheduler 进行幂等派发及恢复。 */
 @Scheduled(fixedDelayString="${pdf.reconcile-ms:2000}")
 public void reconcile(){for(var row:store.jdbc().queryForList("SELECT * FROM pdf_record WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED') ORDER BY created_at LIMIT 20")){try{advance(row);}catch(Exception ignored){/* 短暂网络故障保留待对账状态，不记录敏感响应。 */}}}
 private void advance(Map<String,Object> row){String id=row.get("id").toString();String task=(String)row.get("task_id");if(task==null){int changed=store.jdbc().update("UPDATE pdf_record SET dispatch_started=TRUE WHERE id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')",id);if(changed==0)return;JsonNode created=upstream.scheduler("/api/v1/task-instances",HttpMethod.POST,Map.of("taskName",dispatchValue("pdf.task-name","pdf_process"),"idempotencyKey",dispatchValue("pdf.idempotency-prefix","pdf:")+id,"businessType",dispatchValue("pdf.business-type","PDF_TOOL"),"businessId",id,"priority",40,"parameters",Map.of("workId",id)));task=uuid(created.path("id").asText());store.jdbc().update("UPDATE pdf_record SET task_id=? WHERE id=?",task,id);}
  var now=store.work(id);if("CANCEL_REQUESTED".equals(now.get("status")))upstream.scheduler("/api/v1/task-instances/"+task+"/cancel",HttpMethod.POST,Map.of());JsonNode status=upstream.scheduler("/api/v1/task-instances/"+task,HttpMethod.GET,null);String state=status.path("status").asText();
  if(Set.of("FAILED","CANCELLED","TIMED_OUT","SUCCEEDED").contains(state))store.jdbc().update("UPDATE pdf_record SET status=?,error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')",state.equals("SUCCEEDED")?"FAILED":state,state.equals("SUCCEEDED")?"PDF_010":null,id);
 }
 /** 清理无活动工作占用的过期本地上传，业务引用保留为过期状态。 */
 @Scheduled(fixedDelayString="${pdf.cleanup-ms:60000}")
 public void cleanup(){for(var row:store.jdbc().queryForList("SELECT * FROM pdf_record WHERE expires_at<CURRENT_TIMESTAMP AND status NOT IN ('QUEUED','RUNNING','CANCEL_REQUESTED','EXPIRED') LIMIT 100")){long owner=((Number)row.get("owner_id")).longValue();store.locked(owner,()->{String id=row.get("id").toString();if(row.get("kind").equals("INPUT")){try{Files.deleteIfExists(path(id));}catch(IOException ignored){return false;}}store.jdbc().update("UPDATE pdf_record SET status='EXPIRED' WHERE id=?",id);return true;});}}
 /** 读取可覆盖的调度配置，未配置时保持私有实例的既有取值。 */
 private String dispatchValue(String key,String fallback){String value=env.getProperty(key,"");return value.isBlank()?fallback:value;}
 private List<JsonNode> references(JsonNode request){List<JsonNode> result=new ArrayList<>();request.path("inputIds").forEach(result::add);request.path("attachmentIds").forEach(result::add);return result;}
 private ObjectNode replay(long owner,String kind,String key,ObjectNode body){if(key==null||!key.matches("[A-Za-z0-9:_-]{8,128}"))throw new PdfException(ErrorCode.PDF_001);var rows=store.jdbc().queryForList("SELECT * FROM pdf_record WHERE owner_id=? AND kind=? AND idempotency_key=?",owner,kind,key);if(rows.isEmpty())return null;var row=rows.getFirst();if(!sha(store.canonical(body).getBytes(StandardCharsets.UTF_8)).equals(row.get("payload_digest")))throw new PdfException(ErrorCode.PDF_008);live(row);return view(row);}
 private void quota(long owner){Integer count=store.jdbc().queryForObject("SELECT COUNT(*) FROM pdf_record WHERE owner_id=? AND status IN ('UPLOADING','QUEUED','RUNNING','CANCEL_REQUESTED')",Integer.class,owner);if(count!=null&&count>=10)throw new PdfException(ErrorCode.PDF_016);}
 private void fence(Map<String,Object> row,String task,long token){if(!uuid(task).equals(row.get("task_id"))||token<1||token<((Number)row.get("fencing_token")).longValue()||!Set.of("QUEUED","RUNNING").contains(row.get("status")))throw new PdfException(ErrorCode.PDF_008);}
 private void validateResult(String id,JsonNode result){if(!result.isObject()||result.toString().length()>1_000_000)throw new PdfException(ErrorCode.PDF_010);long total=0;for(String field:List.of("artifacts","previews")){JsonNode list=result.path(field);if(!list.isMissingNode()&&(!list.isArray()||list.size()>101))throw new PdfException(ErrorCode.PDF_010);for(JsonNode a:list){String uri=a.path("storageUri").asText();if(!uri.matches("storage://[A-Za-z0-9_-]+/pdf/"+id+"/[A-Za-z0-9_.-]+")||a.path("bytes").asLong()<1||!a.path("sha256").asText().matches("[a-f0-9]{64}"))throw new PdfException(ErrorCode.PDF_010);total+=a.path("bytes").asLong();if(total>500L*1024*1024)throw new PdfException(ErrorCode.PDF_003);}}}
 private ObjectNode view(Map<String,Object> row){ObjectNode value=store.json().createObjectNode();String kind=row.get("kind").toString();value.put("id",row.get("id").toString());value.put("status",row.get("status").toString());value.put("kind",kind);value.put("createdAt",row.get("created_at").toString());value.put("expiresAt",row.get("expires_at").toString());value.put("errorCode",row.get("error_code")==null?"":row.get("error_code").toString());ObjectNode payload=store.object(row.get("payload_json"));if(payload.has("operation"))value.put("operation",payload.path("operation").asText());if(payload.has("name"))value.put("name",payload.path("name").asText());if(payload.has("clientRevision"))value.set("clientRevision",payload.get("clientRevision"));if(kind.equals("INPUT")){value.put("mediaType",payload.path("mediaType").asText());value.put("bytes",payload.path("bytes").asLong());}ObjectNode result=store.object(row.get("result_json"));for(String field:List.of("artifacts","previews"))for(JsonNode a:result.path(field))((ObjectNode)a).remove(List.of("storageUri","assetId"));if(kind.equals("JOB")&&result.has("operation"))value.set("operation",result.get("operation"));value.set("result",result);return value;}
 private static void live(Map<String,Object> row){if(((Timestamp)row.get("expires_at")).toInstant().isBefore(Instant.now())||row.get("status").equals("EXPIRED"))throw new PdfException(ErrorCode.PDF_012);}
 private void enabled(){if(!env.getProperty("pdf.enabled",Boolean.class,false))throw new PdfException(ErrorCode.PDF_009);}
 private Path path(String id){Path value=root.resolve(uuid(id));if(Files.isSymbolicLink(value))throw new PdfException(ErrorCode.PDF_015);return value;}
 /** 校验 UUID 标识，不允许路径穿越。 */
 public static String uuid(String value){try{return UUID.fromString(value).toString();}catch(Exception e){throw new PdfException(ErrorCode.PDF_001);}}
 /** 计算不可逆内容摘要。 */
 public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new PdfException(ErrorCode.PDF_009);}}
 private static String digest(Path path)throws IOException{try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
}
