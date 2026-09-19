package com.yuyutian.mytools.image.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.image.common.*;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
/** 使用参数化 SQL 保存用户任务与上传资产。 */
@Repository
public class ImageStore {
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 /** 构造持久化存储。 */
 public ImageStore(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
 /** 通过代理方法取得数据库组件，避免读取未初始化的代理实例字段。 */
 public JdbcTemplate jdbc(){return jdbc;}
 /** 通过代理方法取得 JSON 组件。 */
 public ObjectMapper json(){return json;}
 /** 查询用户可访问的任务。 */
 public Map<String,Object> job(long owner,String id) {
  var rows=jdbc.queryForList("SELECT *,TIMESTAMPDIFF(SECOND,created_at,CASE WHEN status IN ('QUEUED','RUNNING','PERSISTING','CANCEL_REQUESTED') THEN CURRENT_TIMESTAMP ELSE updated_at END)*1000 AS elapsed_millis FROM image_job WHERE id=? AND owner_id=?",id,owner);
  if(rows.isEmpty()) throw new ImageException(ErrorCode.IMAGE_004);
  return rows.getFirst();
 }
 /** 查找幂等提交记录。 */
 public List<Map<String,Object>> byKey(long owner,String key) {
  return jdbc.queryForList("SELECT *,TIMESTAMPDIFF(SECOND,created_at,CASE WHEN status IN ('QUEUED','RUNNING','PERSISTING','CANCEL_REQUESTED') THEN CURRENT_TIMESTAMP ELSE updated_at END)*1000 AS elapsed_millis FROM image_job WHERE owner_id=? AND idempotency_key=?",owner,key);
 }
 /** 读取有界用户历史。 */
 public List<Map<String,Object>> list(long owner,int page) {
  return jdbc.queryForList("SELECT *,TIMESTAMPDIFF(SECOND,created_at,CASE WHEN status IN ('QUEUED','RUNNING','PERSISTING','CANCEL_REQUESTED') THEN CURRENT_TIMESTAMP ELSE updated_at END)*1000 AS elapsed_millis FROM image_job WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?",owner,page*20);
 }
 /** 读取需要调度或对账的持久任务。 */
 public List<Map<String,Object>> pending() {
  return jdbc.queryForList("SELECT *,TIMESTAMPDIFF(SECOND,created_at,CASE WHEN status IN ('QUEUED','RUNNING','PERSISTING','CANCEL_REQUESTED') THEN CURRENT_TIMESTAMP ELSE updated_at END)*1000 AS elapsed_millis FROM image_job WHERE status IN ('QUEUED','RUNNING','PERSISTING','CANCEL_REQUESTED') ORDER BY updated_at,id LIMIT 20");
 }
 /** 编码持久 JSON。 */
 public String encode(Object value) {
  try{return json.writeValueAsString(value);}catch(Exception e){throw new ImageException(ErrorCode.IMAGE_002);}
 }
}
