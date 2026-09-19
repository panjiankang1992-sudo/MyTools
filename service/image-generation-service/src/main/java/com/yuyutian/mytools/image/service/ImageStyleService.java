package com.yuyutian.mytools.image.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.image.common.ErrorCode;
import com.yuyutian.mytools.image.common.ImageException;
import com.yuyutian.mytools.image.model.ImageModels.StyleWrite;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.util.*;

/** 管理固定内置风格与账户私有版本，模板仅作字面替换。 */
@Service
public class ImageStyleService {
 private final ImageStore store;
 private final TransactionTemplate transaction;
 private final Map<String,ObjectNode> builtins = new LinkedHashMap<>();
 /** 加载固定版本目录，并构造私有风格事务。 */
 public ImageStyleService(ImageStore store) {
  this.store=store;
  transaction=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(store.jdbc().getDataSource())));
  try(var input=new ClassPathResource("styles/builtin-v1.json").getInputStream()) {
   for(JsonNode item:store.json().readTree(input).path("styles")) {
    ObjectNode style=(ObjectNode)item.deepCopy();style.put("origin","BUILTIN");
    validate(new StyleWrite(style.path("name").asText(),style.path("promptTemplate").asText(),null));
    if(builtins.put(style.path("id").asText(),style)!=null)throw new IllegalStateException("Duplicate built-in style");
   }
  }catch(IOException e){throw new IllegalStateException("Invalid built-in style catalog",e);}
 }
 /** 返回固定内置目录与本人尚未删除的风格。 */
 public List<ObjectNode> list(long owner) {
  List<ObjectNode> result=new ArrayList<>();builtins.values().forEach(value->result.add(value.deepCopy()));
  for(var row:store.jdbc().queryForList("SELECT v.snapshot_json FROM image_style s JOIN image_style_version v ON v.style_id=s.id AND v.version=s.current_version WHERE s.owner_id=? AND s.deleted=FALSE ORDER BY s.created_at,s.id",owner))result.add(parse(row.get("snapshot_json")));
  return result;
 }
 /** 新建风格，以客户端 UUID 提供创建重试幂等性。 */
 public ObjectNode create(long owner,UUID id,StyleWrite request) {
  validate(request);
  if(request.expectedVersion()!=null)throw new ImageException(ErrorCode.IMAGE_002);
  try { return transaction.execute(status->{
   // 相同创建编号只能重放相同的首版内容，且不能越过账户边界。
   var existing=store.jdbc().queryForList("SELECT owner_id,deleted FROM image_style WHERE id=?",id.toString());
   if(!existing.isEmpty()) {
    var row=existing.getFirst();
    if(((Number)row.get("owner_id")).longValue()!=owner||Boolean.TRUE.equals(row.get("deleted")))throw new ImageException(ErrorCode.IMAGE_004);
    var saved=version(owner,id.toString(),1,false);
    if(!saved.path("name").asText().equals(request.name().trim())||!saved.path("promptTemplate").asText().equals(request.promptTemplate().trim()))throw new ImageException(ErrorCode.IMAGE_005);
    return saved;
   }
   ObjectNode snapshot=snapshot(id.toString(),1,request);
   store.jdbc().update("INSERT INTO image_style(id,owner_id,current_version) VALUES(?,?,1)",id.toString(),owner);
   store.jdbc().update("INSERT INTO image_style_version(style_id,version,snapshot_json) VALUES(?,1,?)",id.toString(),store.encode(snapshot));
   return snapshot;
  }); }catch(org.springframework.dao.DuplicateKeyException conflict) {
   // 并发创建由主键约束仲裁，事务回滚后读取胜出的首版。
   ObjectNode saved=version(owner,id.toString(),1,false);
   if(!saved.path("name").asText().equals(request.name().trim())||!saved.path("promptTemplate").asText().equals(request.promptTemplate().trim()))throw new ImageException(ErrorCode.IMAGE_005);
   return saved;
  }
 }
 /** 保存新版本，并拒绝覆盖其他会话的更新。 */
 public ObjectNode update(long owner,UUID id,StyleWrite request) {
  validate(request);
  if(request.expectedVersion()==null||request.expectedVersion()<1||request.expectedVersion()==Integer.MAX_VALUE)throw new ImageException(ErrorCode.IMAGE_002);
  return transaction.execute(status->{
   var rows=store.jdbc().queryForList("SELECT current_version FROM image_style WHERE id=? AND owner_id=? AND deleted=FALSE FOR UPDATE",id.toString(),owner);
   if(rows.isEmpty())throw new ImageException(ErrorCode.IMAGE_004);
   int current=((Number)rows.getFirst().get("current_version")).intValue();
   // 响应丢失后的同内容重试返回已保存版本。
   if(current==request.expectedVersion()+1) {
    ObjectNode saved=version(owner,id.toString(),current,false);
    if(saved.path("name").asText().equals(request.name().trim())&&saved.path("promptTemplate").asText().equals(request.promptTemplate().trim()))return saved;
   }
   if(current!=request.expectedVersion())throw new ImageException(ErrorCode.IMAGE_005);
   ObjectNode snapshot=snapshot(id.toString(),current+1,request);
   store.jdbc().update("INSERT INTO image_style_version(style_id,version,snapshot_json) VALUES(?,?,?)",id.toString(),current+1,store.encode(snapshot));
   store.jdbc().update("UPDATE image_style SET current_version=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=?",current+1,id.toString(),owner);
   return snapshot;
  });
 }
 /** 隐藏本人风格，保留版本以支持历史作品。 */
 public void delete(long owner,UUID id) {
  if(store.jdbc().queryForList("SELECT id FROM image_style WHERE id=? AND owner_id=?",id.toString(),owner).isEmpty())throw new ImageException(ErrorCode.IMAGE_004);
  store.jdbc().update("UPDATE image_style SET deleted=TRUE,updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=?",id.toString(),owner);
 }
 /** 按确定版本解析风格，普通创建不能访问已删除风格。 */
 public ObjectNode resolve(long owner,String id,int version) {
  ObjectNode builtin=builtins.get(id);
  if(builtin!=null) {
   if(builtin.path("version").asInt()!=version)throw new ImageException(ErrorCode.IMAGE_004);
   return builtin.deepCopy();
  }
  return version(owner,id,version,false);
 }
 /** 将文字风格与主体合成，并按当前接口长度限制校验。 */
 public String compose(ObjectNode style,String prompt,String resource,String mode) {
  if(!"krea2-local".equals(resource)||!Set.of("TEXT_TO_IMAGE","IMAGE_TO_IMAGE").contains(mode))throw new ImageException(ErrorCode.IMAGE_002);
  String result=style.path("promptTemplate").asText().replace("{prompt}",prompt.trim());
  if(result.isBlank()||result.length()>4000)throw new ImageException(ErrorCode.IMAGE_002);
  return result;
 }
 private ObjectNode version(long owner,String id,int version,boolean includeDeleted) {
  var rows=store.jdbc().queryForList("SELECT v.snapshot_json FROM image_style s JOIN image_style_version v ON v.style_id=s.id WHERE s.id=? AND s.owner_id=? AND v.version=? AND (s.deleted=FALSE OR ?=TRUE)",id,owner,version,includeDeleted);
  if(rows.isEmpty())throw new ImageException(ErrorCode.IMAGE_004);
  return parse(rows.getFirst().get("snapshot_json"));
 }
 private ObjectNode parse(Object value) {
  try{return (ObjectNode)store.json().readTree(value.toString());}catch(IOException e){throw new ImageException(ErrorCode.IMAGE_007);}
 }
 private ObjectNode snapshot(String id,int version,StyleWrite request) {
  ObjectNode result=store.json().createObjectNode();result.put("id",id);result.put("version",version);
  result.put("name",request.name().trim());result.put("promptTemplate",request.promptTemplate().trim());
  result.put("origin","USER");result.put("kind","PROMPT");result.put("negativePrompt","");
  result.putArray("compatibleModels").add("krea2-local");result.putArray("modes").add("TEXT_TO_IMAGE").add("IMAGE_TO_IMAGE");
  return result;
 }
 private void validate(StyleWrite request) {
  if(request.name()==null||request.name().isBlank()||request.name().length()>80||request.promptTemplate()==null||request.promptTemplate().isBlank()||request.promptTemplate().length()>2000)throw new ImageException(ErrorCode.IMAGE_002);
  String template=request.promptTemplate();int first=template.indexOf("{prompt}");String rest=template.replace("{prompt}","");
  // 模板不允许额外变量、嵌套表达式和多个主体占位符。
  if(first<0||template.indexOf("{prompt}",first+8)>=0||rest.contains("{")||rest.contains("}"))throw new ImageException(ErrorCode.IMAGE_002);
 }
}
