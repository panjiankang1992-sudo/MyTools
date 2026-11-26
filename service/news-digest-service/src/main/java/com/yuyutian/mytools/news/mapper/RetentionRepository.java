package com.yuyutian.mytools.news.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.RetentionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 分批释放正文，保留不可重用的日报及投递身份。 */
@Repository
public class RetentionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final RetentionProperties policy;
    private final CollectionCapacityRepository capacity;
    private static final long DAY_MS = 86_400_000L;
    private static final String ARTICLE_DUE = "a.evidence_expired_ms IS NULL AND a.fetched_ms + 3600000 * CASE WHEN p.evidence_hours < ? THEN p.evidence_hours ELSE ? END <= ?";
    private static final String SNAPSHOT_DUE = "a.evidence_expired_ms IS NULL AND a.observed_ms + 3600000 * CASE WHEN p.evidence_hours < ? THEN p.evidence_hours ELSE ? END <= ?";
    private static final String EDITION_DUE = "e.content_expired_ms IS NULL AND e.created_ms<? AND NOT EXISTS (SELECT 1 FROM news_delivery_batch b WHERE b.edition_id=e.id AND (b.expires_ms>? OR EXISTS (SELECT 1 FROM news_dispatch_outbox d WHERE d.batch_id=b.id AND (d.status NOT IN ('PROVIDER_ACCEPTED','CANCELLED','EXPIRED','FAILED') OR d.retry_generation IS NOT NULL OR d.lease_until_ms>?))))";
    private static final String DISPATCH_DUE = "p.content_expired_ms IS NULL AND p.updated_ms<? AND b.expires_ms<=? AND p.status IN ('PROVIDER_ACCEPTED','CANCELLED','EXPIRED','FAILED') AND p.retry_generation IS NULL AND (p.lease_until_ms IS NULL OR p.lease_until_ms<=?)";

    /** 设置单次事务上限，避免保留策略长时间占用业务行锁。 */
    public RetentionRepository(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate template, RetentionProperties policy, CollectionCapacityRepository capacity) {
        this.jdbc=jdbc;this.json=json;this.policy=policy;
        this.capacity=capacity;
        this.tx=new TransactionTemplate(java.util.Objects.requireNonNull(template.getTransactionManager()));
        this.tx.setTimeout(30);
    }

    /** 来源只可缩短系统正文保留期，延长不会恢复已释放内容。 */
    public void sourcePolicy(String id, int hours, Instant now) {
        if(hours<1 || hours>policy.evidenceHours())throw new NewsException(ErrorCode.NEWS_002);
        tx.executeWithoutResult(status->{
            var found=jdbc.queryForList("SELECT id FROM news_source WHERE id=? FOR UPDATE",String.class,id);
            if(found.isEmpty())throw new NewsException(ErrorCode.NEWS_003);
            int changed=jdbc.update("UPDATE news_source_retention SET evidence_hours=?,updated_ms=? WHERE source_id=?",hours,now.toEpochMilli(),id);
            if(changed==0)jdbc.update("INSERT INTO news_source_retention(source_id,evidence_hours,updated_ms) VALUES(?,?,?)",id,hours,now.toEpochMilli());
        });
    }

    /** 只统计到期候选，不取得租约、不修改正文。 */
    public Map<String,Long> preview(Instant now) {
        long time=now.toEpochMilli();int hours=policy.evidenceHours();
        Map<String,Long> counts=new LinkedHashMap<>();
        counts.put("articleEvidence",count("SELECT COUNT(*) FROM news_article a LEFT JOIN news_source_retention p ON p.source_id=a.source_id WHERE "+ARTICLE_DUE,hours,hours,time));
        counts.put("githubEvidence",count("SELECT COUNT(*) FROM news_github_snapshot a LEFT JOIN news_source_retention p ON p.source_id=a.source_id WHERE "+SNAPSHOT_DUE,hours,hours,time));
        counts.put("articleMetadata",count("SELECT COUNT(*) FROM news_article WHERE fetched_ms<? AND (ranking_ms IS NULL OR ranking_ms<?)",time-policy.metadataDays()*DAY_MS,time-policy.metadataDays()*DAY_MS));
        counts.put("githubSnapshots",count("SELECT COUNT(*) FROM news_github_snapshot WHERE observed_ms<?",time-policy.metadataDays()*DAY_MS));
        counts.put("editions",count("SELECT COUNT(*) FROM news_edition e WHERE "+EDITION_DUE,time-policy.editionDays()*DAY_MS,time,time));
        counts.put("deliveryBodies",count("SELECT COUNT(*) FROM news_dispatch_outbox p JOIN news_delivery_batch b ON b.id=p.batch_id WHERE "+DISPATCH_DUE,time-policy.deliveryDays()*DAY_MS,time,time));
        return counts;
    }

    /** 多实例通过持久租约串行清理，每类最多处理一个批次。 */
    public Map<String,Integer> sweep(Instant now) {
        if(!policy.enabled())throw new NewsException(ErrorCode.NEWS_027);
        String token=UUID.randomUUID().toString();long time=now.toEpochMilli();
        int claimed=jdbc.update("UPDATE news_retention_state SET lease_token=?,lease_until_ms=?,last_started_ms=?,last_status='RUNNING' WHERE id=1 AND (lease_until_ms IS NULL OR lease_until_ms<=?)",token,time+120_000,time,time);
        if(claimed==0)return Map.of();
        try {
            return tx.execute(status->{
                var owned=jdbc.queryForList("SELECT id FROM news_retention_state WHERE id=1 AND lease_token=? AND lease_until_ms>? FOR UPDATE",Integer.class,token,time);
                if(owned.isEmpty())throw new NewsException(ErrorCode.NEWS_016);
                Map<String,Integer> counts=compact(time);
                jdbc.update("UPDATE news_retention_state SET lease_token=NULL,lease_until_ms=NULL,last_finished_ms=?,last_status='SUCCEEDED',counts_json=? WHERE id=1 AND lease_token=?",time,encode(counts),token);
                return counts;
            });
        } catch(RuntimeException exception) {
            // 正文事务失败整体回滚，失败状态不保存异常正文或源数据。
            jdbc.update("UPDATE news_retention_state SET lease_token=NULL,lease_until_ms=NULL,last_finished_ms=?,last_status='FAILED' WHERE id=1 AND lease_token=?",time,token);
            throw new NewsException(ErrorCode.NEWS_009);
        }
    }

    /** 公开脱敏检查点，运维可判断定时器是否持续工作。 */
    public Map<String,Object> checkpoint() {
        return jdbc.queryForMap("SELECT last_started_ms,last_finished_ms,last_status,counts_json FROM news_retention_state WHERE id=1");
    }

    /** 返回采集持久额度，不把它解释为数据库磁盘大小。 */
    public Map<String,Object> capacityStatus() { return capacity.status(); }

    private Map<String,Integer> compact(long now) {
        capacity.lock();
        Map<String,Integer> counts=new LinkedHashMap<>();int limit=policy.batchSize();int hours=policy.evidenceHours();
        var articles=jdbc.query("SELECT a.id,a.payload_json,a.capacity_bytes FROM news_article a LEFT JOIN news_source_retention p ON p.source_id=a.source_id WHERE "+ARTICLE_DUE+" ORDER BY a.fetched_ms,a.id LIMIT ?",(r,i)->new Payload(r.getString(1),r.getString(2),r.getLong(3)),hours,hours,now,limit);
        for(Payload row:articles) {
            ObjectNode node=object(row.body());node.put("evidenceText","");node.put("evidenceExpiredAt",Instant.ofEpochMilli(now).toString());
            String payload=encode(node);long bytes=CollectionCapacityRepository.payloadBytes(payload,false);
            if(bytes>row.bytes())throw new NewsException(ErrorCode.NEWS_009);
            jdbc.update("UPDATE news_article SET payload_json=?,evidence_expired_ms=?,capacity_bytes=? WHERE id=? AND evidence_expired_ms IS NULL",payload,now,bytes,row.id());
            capacity.adjust(0,0,0,bytes-row.bytes());
        }
        counts.put("articleEvidence",articles.size());
        var snapshots=jdbc.query("SELECT a.source_id,a.repository_id,a.bucket_ms,a.payload_json,a.capacity_bytes FROM news_github_snapshot a LEFT JOIN news_source_retention p ON p.source_id=a.source_id WHERE "+SNAPSHOT_DUE+" ORDER BY a.observed_ms LIMIT ?",(r,i)->new Snapshot(r.getString(1),r.getLong(2),r.getLong(3),r.getString(4),r.getLong(5)),hours,hours,now,limit);
        for(Snapshot row:snapshots) {
            ObjectNode node=object(row.body());node.put("description","");
            String payload=encode(node);long bytes=CollectionCapacityRepository.payloadBytes(payload,false);
            if(bytes>row.bytes())throw new NewsException(ErrorCode.NEWS_009);
            jdbc.update("UPDATE news_github_snapshot SET payload_json=?,evidence_expired_ms=?,capacity_bytes=? WHERE source_id=? AND repository_id=? AND bucket_ms=? AND evidence_expired_ms IS NULL",payload,now,bytes,row.source(),row.repository(),row.bucket());
            capacity.adjust(0,0,0,bytes-row.bytes());
        }
        counts.put("githubEvidence",snapshots.size());
        var oldArticles=jdbc.query("SELECT id,capacity_bytes FROM news_article WHERE fetched_ms<? AND (ranking_ms IS NULL OR ranking_ms<?) ORDER BY fetched_ms LIMIT ?",(r,i)->new Payload(r.getString(1),null,r.getLong(2)),now-policy.metadataDays()*DAY_MS,now-policy.metadataDays()*DAY_MS,limit);
        for(Payload row:oldArticles) {
            jdbc.update("DELETE FROM news_article WHERE id=?",row.id());
            capacity.adjust(-1,0,0,-row.bytes());
        }
        counts.put("articleMetadata",oldArticles.size());
        var oldSnapshots=jdbc.query("SELECT source_id,repository_id,bucket_ms,capacity_bytes FROM news_github_snapshot WHERE observed_ms<? ORDER BY observed_ms LIMIT ?",(r,i)->new Snapshot(r.getString(1),r.getLong(2),r.getLong(3),null,r.getLong(4)),now-policy.metadataDays()*DAY_MS,limit);
        for(Snapshot row:oldSnapshots) {
            jdbc.update("DELETE FROM news_github_snapshot WHERE source_id=? AND repository_id=? AND bucket_ms=?",row.source(),row.repository(),row.bucket());
            capacity.adjust(0,-1,0,-row.bytes());
        }
        counts.put("githubSnapshots",oldSnapshots.size());
        List<String> editions=jdbc.queryForList("SELECT e.id FROM news_edition e WHERE "+EDITION_DUE+" ORDER BY e.created_ms LIMIT ?",String.class,now-policy.editionDays()*DAY_MS,now,now,limit);
        for(String id:editions)jdbc.update("UPDATE news_edition SET payload_json=NULL,content_expired_ms=? WHERE id=?",now,id);
        counts.put("editions",editions.size());
        List<String> parts=jdbc.queryForList("SELECT p.id FROM news_dispatch_outbox p JOIN news_delivery_batch b ON b.id=p.batch_id WHERE "+DISPATCH_DUE+" ORDER BY p.updated_ms LIMIT ?",String.class,now-policy.deliveryDays()*DAY_MS,now,now,limit);
        int compacted=0;
        for(String id:parts)compacted+=jdbc.update("UPDATE news_dispatch_outbox SET content_json=NULL,content_expired_ms=?,available_ms=? WHERE id=? AND content_expired_ms IS NULL AND retry_generation IS NULL AND status IN ('PROVIDER_ACCEPTED','CANCELLED','EXPIRED','FAILED') AND (lease_until_ms IS NULL OR lease_until_ms<=?)",now,Long.MAX_VALUE,id,now);
        counts.put("deliveryBodies",compacted);
        return counts;
    }
    private long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);return value==null?0:value;}
    private ObjectNode object(String value){try{return (ObjectNode)json.readTree(value);}catch(JsonProcessingException|ClassCastException e){throw new NewsException(ErrorCode.NEWS_009);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new NewsException(ErrorCode.NEWS_009);}}
    private record Payload(String id,String body,long bytes){}
    private record Snapshot(String source,long repository,long bucket,String body,long bytes){}
}
