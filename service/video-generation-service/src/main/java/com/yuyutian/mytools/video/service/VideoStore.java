package com.yuyutian.mytools.video.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.video.common.ErrorCode;
import com.yuyutian.mytools.video.common.VideoException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
/** 使用参数化 SQL 保存视频任务、输入与上传素材。 */
@Repository
public class VideoStore {
 /** 未结束状态：耗时随时间增长，终态使用持久化结束时间。 */
 private static final String ELAPSED = "TIMESTAMPDIFF(SECOND,created_at,"
  + "CASE WHEN status IN ('QUEUED','RUNNING','PERSISTING','CANCEL_REQUESTED') THEN CURRENT_TIMESTAMP ELSE updated_at END)*1000 AS elapsed_millis";
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 /** 构造持久化存储。 */
 public VideoStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
 /** 通过代理方法取得数据库组件，避免读取未初始化的代理实例字段。 */
 public JdbcTemplate jdbc() { return jdbc; }
 /** 通过代理方法取得 JSON 组件。 */
 public ObjectMapper json() { return json; }
 /** 查询用户可访问的任务。 */
 public Map<String, Object> job(long owner, String id) {
  List<Map<String, Object>> rows = jdbc.queryForList(
   "SELECT *," + ELAPSED + " FROM video_job WHERE id=? AND owner_id=?", id, owner);
  if (rows.isEmpty()) throw new VideoException(ErrorCode.VIDEO_009);
  return rows.getFirst();
 }
 /** 查找幂等提交记录。 */
 public List<Map<String, Object>> byKey(long owner, String key) {
  return jdbc.queryForList("SELECT *," + ELAPSED + " FROM video_job WHERE owner_id=? AND idempotency_key=?",
   owner, key);
 }
 /** 读取有界用户历史。 */
 public List<Map<String, Object>> list(long owner, int page) {
  return jdbc.queryForList("SELECT *," + ELAPSED + " FROM video_job WHERE owner_id=? "
   + "ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?", owner, page * 20);
 }
 /** 读取需要派发或对账的持久任务。 */
 public List<Map<String, Object>> pending() {
  return jdbc.queryForList("SELECT *," + ELAPSED + " FROM video_job "
   + "WHERE status IN ('QUEUED','RUNNING','PERSISTING','CANCEL_REQUESTED') ORDER BY updated_at,id LIMIT 10");
 }
 /** 读取任务的输入素材，按顺序返回。 */
 public List<Map<String, Object>> inputs(String jobId) {
  return jdbc.queryForList("SELECT * FROM video_input WHERE job_id=? ORDER BY ordinal", jobId);
 }
 /** 记录阶段事件，供前台展示真实阶段而不是估算进度。 */
 public void event(String jobId, String stage, String detail) {
  jdbc.update("INSERT INTO video_job_event(id,job_id,stage,detail) VALUES(?,?,?,?)",
   java.util.UUID.randomUUID().toString(), jobId, stage, detail == null ? null : detail.substring(0, Math.min(400, detail.length())));
 }
 /** 编码持久 JSON。 */
 public String encode(Object value) {
  try { return json.writeValueAsString(value); } catch (Exception e) { throw new VideoException(ErrorCode.VIDEO_001); }
 }
}
