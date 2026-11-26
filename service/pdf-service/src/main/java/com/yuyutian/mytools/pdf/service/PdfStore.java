package com.yuyutian.mytools.pdf.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.pdf.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
/** 参数化持久访问与账户级串行变更。 */
@Repository
public class PdfStore {
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 private final TransactionTemplate tx;
 /** 注入持久数据库和事务边界。 */
 public PdfStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) { this.jdbc=jdbc; this.json=json; tx=new TransactionTemplate(manager); }
 /** 返回参数化数据库访问器。 */
 public JdbcTemplate jdbc(){return jdbc;}
 /** 返回统一 JSON 编码器。 */
 public ObjectMapper json(){return json;}
 /** 在 owner 行锁内执行变更，保证多实例配额和幂等一致。 */
 public <T> T locked(long owner, Supplier<T> action) {
  try { jdbc.update("INSERT INTO pdf_owner(owner_id) VALUES(?)",owner); } catch(org.springframework.dao.DuplicateKeyException ignored) { }
  return tx.execute(status -> { jdbc.queryForObject("SELECT owner_id FROM pdf_owner WHERE owner_id=? FOR UPDATE",Long.class,owner); return action.get(); });
 }
 /** 读取本人对象，隐藏跨账户存在性。 */
 public Map<String,Object> owned(long owner,String id,String kind) {
  var rows=jdbc.queryForList("SELECT * FROM pdf_record WHERE id=? AND owner_id=? AND kind=?",id,owner,kind);
  if(rows.isEmpty())throw new PdfException(ErrorCode.PDF_015);
  return rows.getFirst();
 }
 /** 读取 Worker 使用的内部对象。 */
 public Map<String,Object> work(String id) {
  var rows=jdbc.queryForList("SELECT * FROM pdf_record WHERE id=? AND kind IN ('INPUT','PLAN')",id);
  if(rows.isEmpty())throw new PdfException(ErrorCode.PDF_015);return rows.getFirst();
 }
 /** 创建不可变输入载荷与初始状态。 */
 public String insert(long owner,String kind,String state,ObjectNode payload,String key,int ttlSeconds) {
  String id=UUID.randomUUID().toString();String encoded=canonical(payload);
  jdbc.update("INSERT INTO pdf_record(id,owner_id,kind,status,payload_json,payload_digest,idempotency_key,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,owner,kind,state,encoded,PdfService.sha(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)),key,Timestamp.from(Instant.now().plusSeconds(ttlSeconds)));
  return id;
 }
 /** 递归排序对象字段，保持数组顺序，避免幂等键受 JSON 字段顺序影响。 */
 public String canonical(JsonNode value) {return encode(sorted(value));}
 private JsonNode sorted(JsonNode value) {
  if(value.isObject()){ObjectNode result=json.createObjectNode();var names=new ArrayList<String>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)result.set(name,sorted(value.get(name)));return result;}
  if(value.isArray()){var result=json.createArrayNode();for(JsonNode item:value)result.add(sorted(item));return result;}
  return value;
 }
 /** 编码受验证 JSON。 */
 public String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new PdfException(ErrorCode.PDF_001);} }
 /** 解码内部持久 JSON。 */
 public ObjectNode object(Object value) { try{return value==null?json.createObjectNode():(ObjectNode)json.readTree(value.toString());}catch(Exception e){throw new PdfException(ErrorCode.PDF_010);} }
}
