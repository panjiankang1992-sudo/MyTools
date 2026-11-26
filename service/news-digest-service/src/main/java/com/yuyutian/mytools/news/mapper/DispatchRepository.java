package com.yuyutian.mytools.news.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.Model.NewsModels.Edition;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.DeliveryProperties;
import com.yuyutian.mytools.news.service.NewsDeliveryRenderer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** 固定目标、正文分片与调度意图在内容发布事务中一起提交。 */
@Repository
public class DispatchRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final DeliveryProperties properties;
    private final NewsDeliveryRenderer renderer;
    private final ControlCapacityRepository capacity;
    /** 注入本地数据库、确定性渲染器和功能开关。 */
    public DispatchRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json, DeliveryProperties properties, NewsDeliveryRenderer renderer, ControlCapacityRepository capacity) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.json=json; this.properties=properties; this.renderer=renderer; this.capacity=capacity;
    }
    /** 获取个人接收目标的独立配置版本。 */
    public Targets targets(long owner) {
        var revisions=jdbc.queryForList("SELECT revision FROM news_delivery_settings WHERE owner_id=?",Long.class,owner);
        var targets=jdbc.query("SELECT * FROM news_subscription_target WHERE owner_id=? ORDER BY destination_id",(r,i)->new Target(UUID.fromString(r.getString("destination_id")),Channel.valueOf(r.getString("channel")),RenderMode.valueOf(r.getString("render_mode")),r.getBoolean("enabled")),owner);
        return new Targets(revisions.isEmpty()?0:revisions.getFirst(),targets);
    }
    /** 保存已远端核验的目标，移除或停用目标立即请求取消旧批次。 */
    public Targets changeTargets(long owner, long revision, List<Target> targets, Instant now) {
        if(targets.size()>5)throw new NewsException(ErrorCode.NEWS_002);
        try {
            return tx.execute(status->{
                capacity.lock();
                // 与每日扫描使用相同父订阅锁，目标变更只影响之后创建的运行。
                jdbc.queryForList("SELECT id FROM news_subscription WHERE owner_id=? FOR UPDATE",owner);
                jdbc.queryForList("SELECT owner_id FROM news_delivery_settings WHERE owner_id=? FOR UPDATE",owner);
                Targets old=targets(owner);
                if (old.revision()!=revision) throw new NewsException(ErrorCode.NEWS_022);
                if (revision==0) {
                    capacity.reserve(0,1,0,0);
                    jdbc.update("INSERT INTO news_delivery_settings(owner_id,revision,updated_ms) VALUES(?,1,?)",owner,now.toEpochMilli());
                }
                else jdbc.update("UPDATE news_delivery_settings SET revision=revision+1,updated_ms=? WHERE owner_id=?",now.toEpochMilli(),owner);
                jdbc.update("DELETE FROM news_subscription_target WHERE owner_id=?",owner);
                for (Target target:targets) jdbc.update("INSERT INTO news_subscription_target(owner_id,destination_id,channel,render_mode,enabled) VALUES(?,?,?,?,?)",owner,target.destinationId().toString(),target.channel().name(),target.mode().name(),target.enabled());
                Set<UUID> enabled=new HashSet<>(); for (Target target:targets) if(target.enabled()) enabled.add(target.destinationId());
                for (Batch batch:jdbc.query("SELECT * FROM news_delivery_batch WHERE owner_id=? AND cancel_requested=FALSE",this::batchRow,owner)) {
                    if (!enabled.contains(batch.destinationId())) cancelLocked(batch.id(),now);
                }
                return targets(owner);
            });
        } catch (DuplicateKeyException e) { throw new NewsException(ErrorCode.NEWS_022); }
    }
    /** 在创建每日运行的同一事务中固定目标，后续修改不会新增旧日报收件人。 */
    public void capture(UUID run, UUID subscription, long owner, Instant expiry, Instant now) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new NewsException(ErrorCode.NEWS_009);
        var accepted=jdbc.query("SELECT owner_id,subscription_id,delivery_captured FROM news_daily_run WHERE id=? FOR UPDATE",
                (row,index)->new CaptureIdentity(row.getLong(1),row.getString(2),row.getBoolean(3)),run.toString());
        if(accepted.isEmpty() || accepted.getFirst().owner()!=owner
                || !subscription.toString().equals(accepted.getFirst().subscription()))throw new NewsException(ErrorCode.NEWS_016);
        if(accepted.getFirst().captured())return;
        // 固定一次目标，即使最初没有启用投递也不能在后续重试中增加旧日报收件人。
        jdbc.update("UPDATE news_daily_run SET delivery_captured=TRUE WHERE id=?",run.toString());
        if (!properties.enabled()) return;
        jdbc.queryForList("SELECT owner_id FROM news_delivery_settings WHERE owner_id=? FOR UPDATE",owner);
        Targets settings=targets(owner);
        if(settings.targets().size()>5)throw new NewsException(ErrorCode.NEWS_002);
        for (Target target:settings.targets()) {
            if (!target.enabled()) continue;
            jdbc.update("INSERT INTO news_delivery_batch(id,run_id,subscription_id,owner_id,target_revision,destination_id,channel,render_mode,state,expires_ms,created_ms,updated_ms) VALUES(?,?,?,?,?,?,?,?,'WAITING_EDITION',?,?,?)",
                    UUID.randomUUID().toString(),run.toString(),subscription.toString(),owner,settings.revision(),target.destinationId().toString(),target.channel().name(),target.mode().name(),expiry.toEpochMilli(),now.toEpochMilli(),now.toEpochMilli());
        }
    }
    /** 发布时固定内容和分片，提交失败会连同日报和 READY 状态一起回滚。 */
    public void publish(UUID run, UUID editionId, Instant now) {
        for (Batch batch:jdbc.query("SELECT * FROM news_delivery_batch WHERE run_id=? ORDER BY id FOR UPDATE",this::batchRow,run.toString())) {
            if (!batch.state().equals("WAITING_EDITION") || batch.cancelRequested()) continue;
            Edition edition=jdbc.query("SELECT payload_json FROM news_edition WHERE owner_id=? AND id=?",(r,i)->decode(r.getString(1),Edition.class),batch.ownerId(),editionId.toString()).getFirst();
            List<RenderedPart> parts;
            try {
                parts=renderer.render(edition,batch.channel(),batch.mode());
                if(parts.size()>100)throw new NewsException(ErrorCode.NEWS_024);
            }
            catch (NewsException e) {
                if (e.code()!=ErrorCode.NEWS_024) throw e;
                jdbc.update("UPDATE news_delivery_batch SET state='FAILED',error_code='NEWS_024',edition_id=?,updated_ms=? WHERE id=?",editionId.toString(),now.toEpochMilli(),batch.id().toString());
                continue;
            }
            for (RenderedPart part:parts) jdbc.update("INSERT INTO news_dispatch_outbox(id,batch_id,part_index,part_count,content_json,payload_hash,status,available_ms,updated_ms) VALUES(?,?,?,?,?,?,'PENDING',?,?)",
                    UUID.randomUUID().toString(),batch.id().toString(),part.index(),part.count(),encode(part.content()),part.payloadHash(),now.toEpochMilli(),now.toEpochMilli());
            jdbc.update("UPDATE news_delivery_batch SET edition_id=?,render_version=?,state='QUEUED',updated_ms=? WHERE id=?",editionId.toString(),NewsDeliveryRenderer.VERSION,now.toEpochMilli(),batch.id().toString());
        }
    }
    /** 订阅停用取消全部未完成投递；编辑时间只取消尚未生成的运行目标。 */
    public void stopSubscription(UUID subscription, boolean all, Instant now) {
        for (Batch b:jdbc.query("SELECT * FROM news_delivery_batch WHERE subscription_id=?"+(all?"":" AND state='WAITING_EDITION'"),this::batchRow,subscription.toString())) cancelLocked(b.id(),now);
    }
    /** 取消内容运行时同步停止它尚未生成的投递批次。 */
    public void stopRun(UUID run, Instant now) {
        for (Batch b:jdbc.query("SELECT * FROM news_delivery_batch WHERE run_id=? AND state='WAITING_EDITION'",this::batchRow,run.toString())) cancelLocked(b.id(),now);
    }
    /** 批次取消保留已经接受和不确定的真实结果，远端由队列继续核对。 */
    public BatchView cancel(long owner, UUID id, Instant now) {
        return tx.execute(status->{ owned(owner,id,true); cancelLocked(id,now); return view(batch(id,false)); });
    }
    private void cancelLocked(UUID id, Instant now) {
        jdbc.update("UPDATE news_delivery_batch SET cancel_requested=TRUE,state=CASE WHEN state='WAITING_EDITION' THEN 'CANCELLED' ELSE state END,updated_ms=? WHERE id=?",now.toEpochMilli(),id.toString());
        jdbc.update("UPDATE news_dispatch_outbox SET available_ms=? WHERE batch_id=?",now.toEpochMilli(),id.toString());
    }
    /** 查询最近批次，最多五十个，片段不含正文。 */
    public List<BatchView> history(long owner) {
        return jdbc.query("SELECT * FROM news_delivery_batch WHERE owner_id=? ORDER BY created_ms DESC,id DESC LIMIT 50",this::batchRow,owner).stream().map(this::view).toList();
    }
    /** 查询本人单个批次。 */
    public BatchView get(long owner, UUID id) { return view(owned(owner,id,false)); }
    /** 请求重试一个失败片段，不能连带重发其他已成功片段。 */
    public BatchView retry(long owner, UUID batchId, UUID partId, Retry retry, Instant now) {
        return tx.execute(status->{
            Batch batch=owned(owner,batchId,true);
            Dispatch part=part(partId,true);
            if (!part.batchId().equals(batchId)) throw new NewsException(ErrorCode.NEWS_003);
            if (batch.cancelRequested() || !now.isBefore(batch.expiresAt()) || retry.expectedGeneration()<1 || retry.expectedGeneration()>99) throw new NewsException(ErrorCode.NEWS_025);
            if (part.remoteGeneration()==retry.expectedGeneration()+1 || Objects.equals(part.retryGeneration(),retry.expectedGeneration())) return view(batch);
            if (part.remoteGeneration()!=retry.expectedGeneration() || part.remoteId()==null || !Set.of("FAILED","UNKNOWN").contains(part.status())
                    || part.status().equals("UNKNOWN")&&!retry.acknowledgeDuplicateRisk()) throw new NewsException(ErrorCode.NEWS_025);
            jdbc.update("UPDATE news_dispatch_outbox SET retry_generation=?,retry_ack=?,available_ms=? WHERE id=?",retry.expectedGeneration(),retry.acknowledgeDuplicateRisk(),now.toEpochMilli(),partId.toString());
            return view(batch);
        });
    }
    /** 配置修复后恢复被拒绝的原片段身份，不生成新键。 */
    public void redrive(long owner, UUID batchId, UUID partId, Instant now) {
        tx.executeWithoutResult(status->{
            Batch batch=owned(owner,batchId,true); Dispatch part=part(partId,true);
            if (!part.batchId().equals(batchId)) throw new NewsException(ErrorCode.NEWS_003);
            if (!part.status().equals("REJECTED")) throw new NewsException(ErrorCode.NEWS_025);
            jdbc.update("UPDATE news_dispatch_outbox SET status=?,available_ms=?,error_code=NULL WHERE id=?",part.remoteId()==null?"SUBMITTING":"ACCEPTED",now.toEpochMilli(),partId.toString());
        });
    }
    /** 领取一个有限期片段租约，前一片段未确认时后续片段等待。 */
    public Optional<Claim> claim(Instant now) {
        var ids=jdbc.queryForList("""
                SELECT p.id FROM news_dispatch_outbox p JOIN news_delivery_batch b ON b.id=p.batch_id
                WHERE p.content_expired_ms IS NULL AND p.status NOT IN ('PROVIDER_ACCEPTED','CANCELLED','EXPIRED','REJECTED')
                AND (p.status<>'FAILED' OR p.retry_generation IS NOT NULL OR b.cancel_requested=TRUE OR b.expires_ms<=?)
                AND p.available_ms<=? AND (p.lease_until_ms IS NULL OR p.lease_until_ms<=?)
                AND (b.cancel_requested=TRUE OR b.expires_ms<=? OR NOT EXISTS (
                    SELECT 1 FROM news_dispatch_outbox prior WHERE prior.batch_id=p.batch_id AND prior.part_index<p.part_index
                    AND prior.status NOT IN ('PROVIDER_ACCEPTED','CANCELLED','EXPIRED')))
                ORDER BY p.available_ms,p.id LIMIT 20
                """,String.class,now.toEpochMilli(),now.toEpochMilli(),now.toEpochMilli(),now.toEpochMilli());
        for (String id:ids) {
            UUID token=UUID.randomUUID();
            int changed=jdbc.update("UPDATE news_dispatch_outbox SET lease_token=?,lease_until_ms=?,attempts=attempts+1,status=CASE WHEN status='PENDING' THEN 'SUBMITTING' ELSE status END WHERE id=? AND content_expired_ms IS NULL AND available_ms<=? AND (lease_until_ms IS NULL OR lease_until_ms<=?)",
                    token.toString(),now.plusSeconds(60).toEpochMilli(),id,now.toEpochMilli(),now.toEpochMilli());
            if (changed==1) return Optional.of(new Claim(UUID.fromString(id),token));
        }
        return Optional.empty();
    }
    /** 远端操作之后只允许当前租约写回，人工重试意图不能被迟到状态擦除。 */
    public void reconcile(Claim claim, Receipt receipt, Instant now) {
        Dispatch initial=part(claim.id(),false);
        tx.executeWithoutResult(status->{
            Batch batch=batch(initial.batchId(),true); Dispatch part=part(claim.id(),true);
            Integer retry=part.retryGeneration();
            if (retry!=null && receipt.generation()>retry) retry=null;
            if (batch.cancelRequested() || !now.isBefore(batch.expiresAt())) retry=null;
            long next=receipt.status().equals("FAILED") && retry==null && (batch.cancelRequested()||!now.isBefore(batch.expiresAt()))
                    ? Long.MAX_VALUE : now.plusSeconds(receipt.status().equals("UNKNOWN")?300:5).toEpochMilli();
            int changed=jdbc.update("UPDATE news_dispatch_outbox SET remote_id=?,remote_generation=?,status=?,retry_generation=?,error_code=?,available_ms=?,updated_ms=?,lease_token=NULL,lease_until_ms=NULL WHERE id=? AND lease_token=? AND lease_until_ms>?",
                    receipt.id().toString(),receipt.generation(),receipt.status(),retry,receipt.errorCode(),next,now.toEpochMilli(),claim.id().toString(),claim.token().toString(),now.toEpochMilli());
            if(changed!=1) throw new NewsException(ErrorCode.NEWS_016);
        });
    }
    /** 通信失败保留原正文和键，拒绝结果等待显式恢复。 */
    public void failed(Claim claim, boolean permanent, Instant now) {
        Dispatch part=part(claim.id(),false);
        long delay=Math.min(900,5L<<Math.min(8,part.attempts()));
        jdbc.update("UPDATE news_dispatch_outbox SET status=?,error_code='NEWS_023',available_ms=?,lease_token=NULL,lease_until_ms=NULL WHERE id=? AND lease_token=? AND lease_until_ms>?",
                permanent?"REJECTED":part.status(),now.plusSeconds(delay).toEpochMilli(),claim.id().toString(),claim.token().toString(),now.toEpochMilli());
    }
    /** 将无法产出日报的等待批次收敛为真实运行终态。 */
    public void recover(Instant now) {
        jdbc.update("UPDATE news_delivery_batch SET state='EXPIRED',updated_ms=? WHERE state='WAITING_EDITION' AND expires_ms<=?",now.toEpochMilli(),now.toEpochMilli());
        jdbc.update("UPDATE news_delivery_batch SET state='FAILED',error_code='NEWS_019',updated_ms=? WHERE state='WAITING_EDITION' AND run_id IN (SELECT id FROM news_daily_run WHERE status IN ('FAILED','CANCELLED','EXPIRED'))",now.toEpochMilli());
    }
    /** 内部读取被冻结的批次。 */
    public Batch batch(UUID id, boolean lock) { return jdbc.query("SELECT * FROM news_delivery_batch WHERE id=?"+(lock?" FOR UPDATE":""),this::batchRow,id.toString()).stream().findFirst().orElseThrow(()->new NewsException(ErrorCode.NEWS_003)); }
    /** 内部读取被冻结的片段。 */
    public Dispatch part(UUID id, boolean lock) { return jdbc.query("SELECT * FROM news_dispatch_outbox WHERE id=?"+(lock?" FOR UPDATE":""),this::partRow,id.toString()).stream().findFirst().orElseThrow(()->new NewsException(ErrorCode.NEWS_003)); }
    private Batch owned(long owner,UUID id,boolean lock) { Batch value=batch(id,lock); if(value.ownerId()!=owner)throw new NewsException(ErrorCode.NEWS_003);return value; }
    private BatchView view(Batch b) {
        List<PartView> parts=jdbc.query("SELECT id,part_index,part_count,status,remote_generation,error_code,retry_generation FROM news_dispatch_outbox WHERE batch_id=? ORDER BY part_index",(r,i)->new PartView(UUID.fromString(r.getString(1)),r.getInt(2),r.getInt(3),r.getString(4),r.getInt(5),r.getString(6),r.getObject(7)!=null),b.id().toString());
        String status=b.state();
        if(!parts.isEmpty()) {
            Set<String> states=new HashSet<>();for(PartView p:parts)states.add(p.status());
            if(states.size()==1)status=parts.getFirst().status();
            else if(states.contains("UNKNOWN"))status="UNKNOWN";
            else if(states.stream().anyMatch(s->Set.of("PENDING","SUBMITTING","ACCEPTED","SENDING").contains(s)))status="IN_PROGRESS";
            else status="PARTIAL";
        }
        return new BatchView(b.id(),b.runId(),b.editionId(),b.destinationId(),b.channel(),b.mode(),status,b.cancelRequested(),b.expiresAt(),b.createdAt(),b.errorCode(),parts);
    }
    private Batch batchRow(ResultSet r,int i)throws SQLException {return new Batch(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("run_id")),UUID.fromString(r.getString("subscription_id")),r.getLong("owner_id"),r.getLong("target_revision"),UUID.fromString(r.getString("destination_id")),Channel.valueOf(r.getString("channel")),RenderMode.valueOf(r.getString("render_mode")),uuid(r.getString("edition_id")),r.getString("state"),r.getBoolean("cancel_requested"),Instant.ofEpochMilli(r.getLong("expires_ms")),Instant.ofEpochMilli(r.getLong("created_ms")),r.getString("error_code"));}
    private Dispatch partRow(ResultSet r,int i)throws SQLException {return new Dispatch(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("batch_id")),r.getInt("part_index"),r.getInt("part_count"),(r.getString("content_json")==null?null:decode(r.getString("content_json"),Content.class)),r.getString("payload_hash"),r.getString("status"),uuid(r.getString("remote_id")),r.getInt("remote_generation"),(Integer)r.getObject("retry_generation"),r.getBoolean("retry_ack"),r.getInt("attempts"));}
    private UUID uuid(String value){return value==null?null:UUID.fromString(value);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new NewsException(ErrorCode.NEWS_009);}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(JsonProcessingException e){throw new NewsException(ErrorCode.NEWS_009);}}
    private record CaptureIdentity(long owner,String subscription,boolean captured) { }
}
