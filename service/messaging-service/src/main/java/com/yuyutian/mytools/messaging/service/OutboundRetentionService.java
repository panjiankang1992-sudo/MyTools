package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.config.OutboundRetentionProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.model.OutboundModels.Content;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 按明确截止或普通保留策略释放正文，保留投递身份和未知结果审计。 */
@Service
public class OutboundRetentionService {
    private static final long DAY_MS=86_400_000L;
    private static final String DEADLINE_DUE="content_json IS NOT NULL AND content_expired_ms IS NULL AND content_until_ms<=?";
    private static final String BODY_DUE="content_expired_ms IS NULL AND content_json IS NOT NULL AND status IN ('PROVIDER_ACCEPTED','FAILED','CANCELLED','EXPIRED') AND expires_ms<=? AND updated_ms<? AND (relay_until_ms IS NULL OR relay_until_ms<=?)";
    private static final String ATTEMPT_DUE="a.finished_ms<? AND a.status<>'SENDING' AND (a.status<>'UNKNOWN' OR a.resolution IS NOT NULL) AND d.content_expired_ms IS NOT NULL AND d.expires_ms<=? AND d.status IN ('PROVIDER_ACCEPTED','FAILED','CANCELLED','EXPIRED')";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final OutboundRetentionProperties policy;
    private final com.yuyutian.mytools.messaging.repository.OutboundCapacityRepository capacity;
    /** 新版清理独立使用短事务，不接触旧版邮件和入站表。 */
    public OutboundRetentionService(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,OutboundRetentionProperties policy,com.yuyutian.mytools.messaging.repository.OutboundCapacityRepository capacity){this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(30);this.policy=policy;this.capacity=capacity;}
    /** 只返回有界策略、候选数量及检查点，不提供地址和正文。 */
    public Map<String,Object> status(Instant now) {
        try {
            long time=now.toEpochMilli();Map<String,Object> result=new LinkedHashMap<>();
            result.put("capacity",capacity.status());
            result.put("deadlineCleanup",Map.of("enabled",true,"due",count("SELECT COUNT(*) FROM messaging_outbound WHERE "+DEADLINE_DUE,time),
                    "checkpoint",jdbc.queryForMap("SELECT last_started_ms,last_finished_ms,last_status,counts_json FROM messaging_outbound_deadline_state WHERE id=1")));
            result.put("enabled",policy.enabled());result.put("observedAt",now);
            result.put("policy",Map.of("contentDays",policy.contentDays(),"attemptDays",policy.attemptDays(),"batchSize",policy.batchSize()));
            result.put("due",Map.of("bodies",count("SELECT COUNT(*) FROM messaging_outbound WHERE "+BODY_DUE,time,time-policy.contentDays()*DAY_MS,time),
                    "attempts",count("SELECT COUNT(*) FROM messaging_outbound_attempt a JOIN messaging_outbound d ON d.id=a.delivery_id WHERE "+ATTEMPT_DUE,time-policy.attemptDays()*DAY_MS,time)));
            result.put("protected",Map.of("unknown",count("SELECT COUNT(*) FROM messaging_outbound WHERE status='UNKNOWN'"),"sending",count("SELECT COUNT(*) FROM messaging_outbound WHERE status='SENDING'")));
            result.put("checkpoint",jdbc.queryForMap("SELECT last_started_ms,last_finished_ms,last_status,counts_json FROM messaging_outbound_retention_state WHERE id=1"));
            return result;
        } catch(RuntimeException exception){throw new OutboundException(ErrorCode.OUTBOUND_RETENTION_FAILED);}
    }
    /** 使用持久租约完成一个批次，失去租约或损坏载荷会整体回滚。 */
    public Map<String,Integer> sweep(Instant now) {
        if(!policy.enabled())throw new OutboundException(ErrorCode.OUTBOUND_RETENTION_DISABLED);
        return sweep(now,false);
    }
    /** 明确的正文截止不受普通保留开关控制，到期状态仍保留用于取消和人工核对。 */
    public Map<String,Integer> sweepDeadlines(Instant now) {
        try {
            if(count("SELECT COUNT(*) FROM messaging_outbound WHERE "+DEADLINE_DUE,now.toEpochMilli())==0)return Map.of("deadlineBodies",0);
            return sweep(now,true);
        } catch(RuntimeException exception){throw new OutboundException(ErrorCode.OUTBOUND_RETENTION_FAILED);}
    }
    private Map<String,Integer> sweep(Instant now,boolean deadlinesOnly) {
        String token=UUID.randomUUID().toString();long time=now.toEpochMilli();
        // 两类清理各有检查点，硬截止的空轮次不能覆盖普通保留清理的失败证据。
        String stateTable=deadlinesOnly?"messaging_outbound_deadline_state":"messaging_outbound_retention_state";
        try {
            int claimed=jdbc.update("UPDATE "+stateTable+" SET lease_token=?,lease_until_ms=?,last_started_ms=?,last_status='RUNNING' WHERE id=1 AND (lease_until_ms IS NULL OR lease_until_ms<=?)",token,time+120_000,time,time);
            if(claimed==0)return Map.of();
            return tx.execute(status->{
                var owned=jdbc.queryForList("SELECT id FROM "+stateTable+" WHERE id=1 AND lease_token=? AND lease_until_ms>? FOR UPDATE",Integer.class,token,time);
                if(owned.isEmpty())throw new OutboundException(ErrorCode.OUTBOUND_RETENTION_FAILED);
                capacity.lock();
                var expired=jdbc.query("SELECT id,capacity_bytes FROM messaging_outbound WHERE "+DEADLINE_DUE+" ORDER BY content_until_ms,id LIMIT ? FOR UPDATE",
                        (r,i)->new ExpiredBody(r.getString(1),r.getLong(2)),time,policy.batchSize());
                for(ExpiredBody row:expired) {
                    // 到期副本仅释放正文；UNKNOWN/SENDING 的状态、尝试与身份摘要都不能被抹掉。
                    jdbc.update("UPDATE messaging_outbound SET content_json=NULL,capacity_bytes=0,content_expired_ms=?,updated_ms=CASE WHEN status='ACCEPTED' THEN ? ELSE updated_ms END,error_code=CASE WHEN status='ACCEPTED' THEN 'MESSAGING_028' ELSE error_code END,status=CASE WHEN status='ACCEPTED' THEN 'EXPIRED' ELSE status END WHERE id=?",time,time,row.id());
                    capacity.releaseBytes(row.bytes());
                }
                if(deadlinesOnly) {
                    Map<String,Integer> counts=Map.of("deadlineBodies",expired.size());
                    jdbc.update("UPDATE "+stateTable+" SET lease_token=NULL,lease_until_ms=NULL,last_finished_ms=?,last_status='SUCCEEDED',counts_json=? WHERE id=1 AND lease_token=?",time,encode(counts),token);
                    return counts;
                }
                var bodies=jdbc.query("SELECT id,content_json,capacity_bytes FROM messaging_outbound WHERE "+BODY_DUE+" ORDER BY updated_ms,id LIMIT ? FOR UPDATE",(r,i)->new Body(r.getString(1),r.getString(2),r.getLong(3)),time,time-policy.contentDays()*DAY_MS,time,policy.batchSize());
                for(Body row:bodies) {
                    // 先保存严格内容身份摘要，再释放正文，空与缺省 HTML 不会被混同。
                    Content content=decode(row.content());
                    jdbc.update("UPDATE messaging_outbound SET content_json=NULL,capacity_bytes=0,content_identity_hash=?,content_expired_ms=? WHERE id=?",OutboundSecurity.identityHash(content),time,row.id());
                    capacity.releaseBytes(row.bytes());
                }
                var attempts=jdbc.queryForList("SELECT a.id FROM messaging_outbound_attempt a JOIN messaging_outbound d ON d.id=a.delivery_id WHERE "+ATTEMPT_DUE+" ORDER BY a.finished_ms,a.id LIMIT ?",String.class,time-policy.attemptDays()*DAY_MS,time,policy.batchSize());
                for(String id:attempts)jdbc.update("DELETE FROM messaging_outbound_attempt WHERE id=?",id);
                Map<String,Integer> counts=new LinkedHashMap<>();counts.put("bodies",bodies.size());counts.put("attempts",attempts.size());
                if(!expired.isEmpty())counts.put("deadlineBodies",expired.size());
                jdbc.update("UPDATE "+stateTable+" SET lease_token=NULL,lease_until_ms=NULL,last_finished_ms=?,last_status='SUCCEEDED',counts_json=? WHERE id=1 AND lease_token=?",time,encode(counts),token);
                return counts;
            });
        } catch(RuntimeException exception) {
            try {jdbc.update("UPDATE "+stateTable+" SET lease_token=NULL,lease_until_ms=NULL,last_finished_ms=?,last_status='FAILED' WHERE id=1 AND lease_token=?",time,token);}
            catch(RuntimeException ignored){/* 数据库不可用时不输出底层错误或请求内容。 */}
            throw new OutboundException(ErrorCode.OUTBOUND_RETENTION_FAILED);
        }
    }
    private long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);return value==null?0:value;}
    private Content decode(String value){try{return json.readValue(value,Content.class);}catch(JsonProcessingException e){throw new OutboundException(ErrorCode.OUTBOUND_RETENTION_FAILED);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new OutboundException(ErrorCode.OUTBOUND_RETENTION_FAILED);}}
    private record Body(String id,String content,long bytes){}
    private record ExpiredBody(String id,long bytes){}
}
