package com.yuyutian.mytools.messaging.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 新版投递独立持久化，所有者查询始终包含 owner 条件。 */
@Repository
public class OutboundRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OutboundCapacityRepository capacity;
    /** 注入参数化数据库访问和 JSON 编解码器。 */
    public OutboundRepository(JdbcTemplate jdbc, ObjectMapper json, OutboundCapacityRepository capacity) { this.jdbc = jdbc; this.json = json; this.capacity=capacity; }

    /** 新准入先锁容量，已有投递的执行、停用和对账无需此锁。 */
    public void lockCapacity() { capacity.lock(); }

    /** 返回本人绑定目标。 */
    public List<Destination> destinations(long owner) {
        return jdbc.query("SELECT * FROM messaging_destination WHERE owner_id=? ORDER BY created_ms DESC LIMIT 100",
                (r, i) -> destination(r), owner);
    }
    /** 按幂等键查找绑定。 */
    public Optional<UUID> binding(long owner, String key) { return binding(owner,key,false); }
    /** 并发重放使用当前读，防止等待容量锁后仍读取旧快照。 */
    public Optional<UUID> binding(long owner, String key, boolean lock) {
        return jdbc.query("SELECT id FROM messaging_destination WHERE owner_id=? AND binding_key=?"+(lock?" FOR UPDATE":""),
                (r, i) -> UUID.fromString(r.getString(1)), owner, key).stream().findFirst();
    }
    /** 锁住目标后检查启用状态，和停用请求共享事务边界。 */
    public Optional<Destination> destination(long owner, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM messaging_destination WHERE owner_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (r, i) -> destination(r), owner, id.toString()).stream().findFirst();
    }
    /** 保存已经确认所有权的加密绑定。 */
    public void bind(UUID id, Binding b, String encrypted, Instant now) {
        capacity.reserve(1,0,0,0);
        jdbc.update("INSERT INTO messaging_destination(id,owner_id,binding_key,channel,label,secret_cipher,verification_reference,enabled,created_ms,updated_ms,capacity_recorded) VALUES(?,?,?,?,?,?,?,TRUE,?,?,TRUE)",
                id.toString(), b.ownerId(), b.bindingKey(), b.channel().name(), b.label(), encrypted, b.verificationReference(), now.toEpochMilli(), now.toEpochMilli());
    }
    /** 仅提供服务内部所需密文。 */
    public String secret(long owner, UUID id) {
        return jdbc.queryForObject("SELECT secret_cipher FROM messaging_destination WHERE owner_id=? AND id=?", String.class, owner, id.toString());
    }
    /** 停用目的地同时取消尚未进入提供方调用的请求。 */
    public void disable(long owner, UUID id, Instant now) {
        jdbc.update("UPDATE messaging_destination SET enabled=FALSE,updated_ms=? WHERE owner_id=? AND id=?", now.toEpochMilli(), owner, id.toString());
        jdbc.update("UPDATE messaging_outbound SET status='CANCELLED',updated_ms=? WHERE owner_id=? AND destination_id=? AND status='ACCEPTED'", now.toEpochMilli(), owner, id.toString());
    }
    /** 幂等查找请求。 */
    public Optional<Delivery> byKey(long owner, String key) { return byKey(owner,key,false); }
    /** 当前读在容量锁后重查同键，重复请求不会再次占用额度。 */
    public Optional<Delivery> byKey(long owner, String key, boolean lock) {
        return jdbc.query("SELECT * FROM messaging_outbound WHERE owner_id=? AND idempotency_key=?"+(lock?" FOR UPDATE":""), this::delivery, owner, key).stream().findFirst();
    }
    /** 服务内部按标识读取，可选排他锁。 */
    public Optional<Delivery> get(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM messaging_outbound WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::delivery, id.toString()).stream().findFirst();
    }
    /** 保存完整冻结内容和调度意图，不在事务中调用外部服务。 */
    public void insert(UUID id, Create request, Channel channel, Instant now) {
        String content=encode(request.content());
        long bytes=OutboundCapacityRepository.payloadBytes(content);
        capacity.reserve(0,1,0,bytes);
        jdbc.update("INSERT INTO messaging_outbound(id,owner_id,idempotency_key,destination_id,channel,content_json,payload_hash,status,expires_ms,next_attempt_ms,created_ms,updated_ms,capacity_kind,capacity_bytes,content_identity_hash,content_until_ms) VALUES(?,?,?,?,?,?,?,'ACCEPTED',?,?,?,?,'DELIVERY',?,?,?)",
                id.toString(), request.ownerId(), request.idempotencyKey(), request.destinationId().toString(), channel.name(), content,
                request.payloadHash(), request.expiresAt().toEpochMilli(), now.toEpochMilli(), now.toEpochMilli(), now.toEpochMilli(),bytes,com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity.identityHash(request.content()),epoch(request.contentExpiresAt()));
    }
    /** 未知创建的取消只保存严格身份摘要，独立额度不会被普通消息占满。 */
    public void insertCancellation(UUID id,Create request,Channel channel,Instant now) {
        insertCancellation(id,new CancelIdentity(request.ownerId(),request.idempotencyKey(),request.destinationId(),request.payloadHash(),
                com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity.identityHash(request.content()),request.expiresAt(),request.contentExpiresAt()),channel,now);
    }
    /** 无正文取消复用独立保留额度，冻结所有身份字段后直接进入终态。 */
    public void insertCancellation(UUID id,CancelIdentity request,Channel channel,Instant now) {
        capacity.reserve(0,0,1,0);
        jdbc.update("INSERT INTO messaging_outbound(id,owner_id,idempotency_key,destination_id,channel,payload_hash,status,expires_ms,next_attempt_ms,created_ms,updated_ms,content_identity_hash,content_expired_ms,capacity_kind,capacity_bytes,content_until_ms) VALUES(?,?,?,?,?,?,'CANCELLED',?,?,?,?,?,?,'CANCEL_ONLY',0,?)",
                id.toString(),request.ownerId(),request.idempotencyKey(),request.destinationId().toString(),channel.name(),request.payloadHash(),
                request.expiresAt().toEpochMilli(),now.toEpochMilli(),now.toEpochMilli(),now.toEpochMilli(),request.contentIdentityHash(),now.toEpochMilli(),epoch(request.contentExpiresAt()));
    }
    /** 列出到期调度项，每轮只处理有限条目。 */
    public List<UUID> due(Instant now) {
        return jdbc.query("SELECT id FROM messaging_outbound WHERE status='ACCEPTED' AND next_attempt_ms<=? AND (relay_until_ms IS NULL OR relay_until_ms<=?) ORDER BY created_ms LIMIT 20",
                (r, i) -> UUID.fromString(r.getString(1)), now.toEpochMilli(), now.toEpochMilli());
    }
    /** 使用短租约避免多个实例同时调度同一项。 */
    public boolean claim(UUID id, UUID token, Instant now) {
        return jdbc.update("UPDATE messaging_outbound SET relay_token=?,relay_until_ms=?,relay_attempts=relay_attempts+1 WHERE id=? AND status='ACCEPTED' AND (relay_until_ms IS NULL OR relay_until_ms<=?)",
                token.toString(), now.plusSeconds(60).toEpochMilli(), id.toString(), now.toEpochMilli()) == 1;
    }
    /** 仅当前短租约可绑定任务，丢失响应后继续复用同一任务键。 */
    public void task(UUID id, UUID token, UUID taskId, Instant now) {
        jdbc.update("UPDATE messaging_outbound SET task_id=?,relay_token=NULL,relay_until_ms=NULL,updated_ms=?,next_attempt_ms=?,error_code=NULL WHERE id=? AND relay_token=? AND relay_until_ms>?",
                taskId.toString(), now.toEpochMilli(), now.plusSeconds(30).toEpochMilli(), id.toString(), token.toString(), now.toEpochMilli());
    }
    /** 已绑定任务的终态不能覆盖真正开始发送后的结果。 */
    public void reconcileTask(UUID id, UUID token, String taskStatus, Instant now) {
        boolean cancelled = "CANCELLED".equals(taskStatus);
        boolean ended = java.util.Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(taskStatus);
        jdbc.update("UPDATE messaging_outbound SET status=?,error_code=?,relay_token=NULL,relay_until_ms=NULL,updated_ms=?,next_attempt_ms=? WHERE id=? AND relay_token=? AND relay_until_ms>? AND status='ACCEPTED'",
                ended ? cancelled ? "CANCELLED" : "FAILED" : "ACCEPTED", ended ? "SCHEDULER_ENDED_WITHOUT_SEND" : null,
                now.toEpochMilli(), now.plusSeconds(30).toEpochMilli(), id.toString(), token.toString(), now.toEpochMilli());
    }
    /** 调度暂时失败后退避，保持请求和任务身份不变。 */
    public void defer(UUID id, UUID token, Instant now) {
        jdbc.update("UPDATE messaging_outbound SET next_attempt_ms=?,relay_token=NULL,relay_until_ms=NULL,error_code='SCHEDULER_UNAVAILABLE' WHERE id=? AND relay_token=?",
                now.plusSeconds(30).toEpochMilli(), id.toString(), token.toString());
    }
    /** 仅在已持有行锁的事务内开始一次提供方尝试。 */
    public UUID begin(Delivery d, UUID taskId, long fence, Instant now) {
        UUID attempt = UUID.randomUUID();
        jdbc.update("INSERT INTO messaging_outbound_attempt(id,delivery_id,generation,fencing_token,status,started_ms) VALUES(?,?,?,?,'SENDING',?)",
                attempt.toString(), d.id().toString(), d.generation(), fence, now.toEpochMilli());
        jdbc.update("UPDATE messaging_outbound SET status='SENDING',task_id=?,attempt_id=?,last_fence=?,updated_ms=?,error_code=NULL WHERE id=?",
                taskId.toString(), attempt.toString(), fence, now.toEpochMilli(), d.id().toString());
        return attempt;
    }
    /** 尝试标识隔离迟到结果，人工处理后的旧执行不得覆盖新结果。 */
    public void finish(UUID id, UUID attempt, String state, String reference, String error, Instant now) {
        int updated = jdbc.update("UPDATE messaging_outbound SET status=?,provider_reference=?,error_code=?,updated_ms=? WHERE id=? AND attempt_id=? AND status IN ('SENDING','UNKNOWN')",
                state, reference, error, now.toEpochMilli(), id.toString(), attempt.toString());
        if (updated == 1) {
            // 与投递状态同事务保存本次提供方调用结果。
            jdbc.update("UPDATE messaging_outbound_attempt SET status=?,error_code=?,finished_ms=? WHERE id=? AND resolution IS NULL",
                    state, error, now.toEpochMilli(), attempt.toString());
        }
    }
    /** 取消、过期和人工重试只在持有行锁的事务中调用。 */
    public void state(UUID id, String state, Instant now) {
        jdbc.update("UPDATE messaging_outbound SET status=?,updated_ms=? WHERE id=?", state, now.toEpochMilli(), id.toString());
    }
    /** 正文期限阻止尚未发送的请求，已经在途的真实结果不能改为未发送。 */
    public void contentExpired(UUID id,Instant now) {
        jdbc.update("UPDATE messaging_outbound SET status='EXPIRED',error_code='MESSAGING_028',updated_ms=? WHERE id=? AND status='ACCEPTED'",now.toEpochMilli(),id.toString());
    }
    /** 新一代调度必须使用新的任务幂等键，历史尝试仍然保留。 */
    public void retry(Delivery d, String resolution, Instant now) {
        jdbc.update("UPDATE messaging_outbound_attempt SET resolution=?,finished_ms=? WHERE id=?", resolution, now.toEpochMilli(), d.attemptId() == null ? "" : d.attemptId().toString());
        jdbc.update("UPDATE messaging_outbound SET status='ACCEPTED',generation=generation+1,task_id=NULL,attempt_id=NULL,last_fence=0,error_code=NULL,provider_reference=NULL,relay_token=NULL,relay_until_ms=NULL,next_attempt_ms=?,updated_ms=? WHERE id=?",
                now.toEpochMilli(), now.toEpochMilli(), d.id().toString());
    }
    /** 人工核对保留依据，同时清除旧执行的结果写入资格。 */
    public void resolve(Delivery d, Resolution resolution, Instant now) {
        jdbc.update("UPDATE messaging_outbound_attempt SET resolution=?,status=?,finished_ms=? WHERE id=?", resolution.reference(), resolution.outcome(), now.toEpochMilli(), d.attemptId().toString());
        jdbc.update("UPDATE messaging_outbound SET status=?,attempt_id=NULL,error_code=NULL,updated_ms=? WHERE id=?",
                resolution.outcome().equals("NOT_SENT") ? "FAILED" : "PROVIDER_ACCEPTED", now.toEpochMilli(), d.id().toString());
    }
    /** 服务重启或提供方调用中断后，不自动重发无法确认结果的请求。 */
    public void recover(Instant now) {
        jdbc.update("UPDATE messaging_outbound SET status='EXPIRED',error_code=CASE WHEN content_until_ms<=? THEN 'MESSAGING_028' ELSE error_code END,updated_ms=? WHERE status='ACCEPTED' AND (expires_ms<=? OR content_until_ms<=?)",now.toEpochMilli(),now.toEpochMilli(),now.toEpochMilli(),now.toEpochMilli());
        jdbc.update("UPDATE messaging_outbound SET status='UNKNOWN',error_code='MESSAGING_023',updated_ms=? WHERE status='SENDING' AND updated_ms<?", now.toEpochMilli(), now.minusSeconds(180).toEpochMilli());
        jdbc.update("UPDATE messaging_outbound_attempt SET status='UNKNOWN',error_code='MESSAGING_023' WHERE status='SENDING' AND started_ms<?", now.minusSeconds(180).toEpochMilli());
    }
    /** 序列化冻结内容。 */
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Outbound serialization failed"); }
    }
    /** 解码持久数据，失败时不输出原始内容。 */
    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Outbound record is invalid"); }
    }
    private Destination destination(ResultSet r) throws SQLException {
        return new Destination(UUID.fromString(r.getString("id")), Channel.valueOf(r.getString("channel")), r.getString("label"), r.getBoolean("enabled"), Instant.ofEpochMilli(r.getLong("created_ms")));
    }
    private Delivery delivery(ResultSet r, int index) throws SQLException {
        return new Delivery(UUID.fromString(r.getString("id")), r.getLong("owner_id"), r.getString("idempotency_key"), UUID.fromString(r.getString("destination_id")),
                Channel.valueOf(r.getString("channel")), (r.getString("content_json")==null?null:decode(r.getString("content_json"), Content.class)), r.getString("payload_hash"), r.getString("status"), r.getInt("generation"),
                uuid(r.getString("task_id")), uuid(r.getString("attempt_id")), r.getLong("last_fence"), Instant.ofEpochMilli(r.getLong("expires_ms")), Instant.ofEpochMilli(r.getLong("updated_ms")), r.getString("error_code"),r.getString("content_identity_hash"),instant(r.getObject("content_until_ms",Long.class)));
    }
    private Long epoch(Instant value){return value==null?null:value.toEpochMilli();}
    private Instant instant(Long value){return value==null?null:Instant.ofEpochMilli(value);}
    private UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
