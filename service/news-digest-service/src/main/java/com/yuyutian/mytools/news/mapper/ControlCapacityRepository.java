package com.yuyutian.mytools.news.mapper;

import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.ControlCapacityProperties;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 接收新工作时预留身份，已接收任务的内容提交不再竞争剩余额度。 */
@Repository
public class ControlCapacityRepository {
    private final JdbcTemplate jdbc;
    private final ControlCapacityProperties limits;

    /** 注入共享账本和本实例配置。 */
    public ControlCapacityRepository(JdbcTemplate jdbc,ControlCapacityProperties limits) { this.jdbc=jdbc;this.limits=limits; }

    /** 新工作先取得容量锁，再取得订阅锁；完成和取消已有工作无需此锁。 */
    public void lock() {
        requireTransaction();
        jdbc.queryForObject("SELECT id FROM news_control_capacity WHERE id=1 FOR UPDATE",Integer.class);
    }

    /** 原子扣除新订阅、目标配置、每日运行或预览身份的额度。 */
    public void reserve(long subscriptions,long owners,long runs,long previews) {
        requireTransaction();
        long[] used=jdbc.queryForObject("SELECT subscription_rows,target_owners,daily_runs,preview_identities FROM news_control_capacity WHERE id=1 FOR UPDATE",
                (r,i)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)});
        if(used==null)throw new NewsException(ErrorCode.NEWS_009);
        long[] delta={subscriptions,owners,runs,previews};
        long[] maximum={limits.subscriptions(),limits.targetOwners(),limits.dailyRuns(),limits.previewIdentities()};
        for(int index=0;index<used.length;index++) {
            // 身份长期保留，不能靠取消、正文到期或失败重试把已占用的键重新出售。
            if(delta[index]<0)throw new NewsException(ErrorCode.NEWS_009);
            used[index]=Math.addExact(used[index],delta[index]);
            if(delta[index]>0 && used[index]>maximum[index])throw new NewsException(ErrorCode.NEWS_029);
        }
        jdbc.update("UPDATE news_control_capacity SET subscription_rows=?,target_owners=?,daily_runs=?,preview_identities=? WHERE id=1",used[0],used[1],used[2],used[3]);
    }

    /** 地区绑定和日报共用一个身份；定时生成使用接收运行时已预留的槽位。 */
    public void admitContent(long owner,String key,String requestHash) {
        requireTransaction();
        String hash=ContentIdentity.hash(key);
        if(key.startsWith("news-run:")) {
            // 定时运行已占用容量，不在持有发布订阅锁时反向等待全局额度锁。
            UUID run;
            try {run=UUID.fromString(key.substring("news-run:".length()));}
            catch(IllegalArgumentException exception){throw new NewsException(ErrorCode.NEWS_002);}
            if(!key.equals("news-run:"+run))throw new NewsException(ErrorCode.NEWS_002);
            var rows=jdbc.query("SELECT owner_id,cutoff_ms,zone_id FROM news_daily_run WHERE id=? FOR UPDATE",(r,i)->
                    r.getLong(1)==owner && requestHash.equals(ContentIdentity.hash(Instant.ofEpochMilli(r.getLong(2))+"|"+r.getString(3))),run.toString());
            if(rows.isEmpty() || !rows.getFirst())throw new NewsException(ErrorCode.NEWS_016);
            if(existingCurrent(owner,hash,requestHash))return;
            jdbc.update("INSERT INTO news_content_identity(owner_id,idempotency_hash,request_hash,identity_kind,run_id) VALUES(?,?,?,'SCHEDULED',?)",owner,hash,requestHash,run.toString());
            return;
        }
        lock();
        if(existingCurrent(owner,hash,requestHash))return;
        reserve(0,0,0,1);
        jdbc.update("INSERT INTO news_content_identity(owner_id,idempotency_hash,request_hash,identity_kind) VALUES(?,?,?,'PREVIEW')",owner,hash,requestHash);
    }

    /** 管理查询在一个快照中核对账本和实际身份，不修改任何额度。 */
    public Map<String,Object> status() {
        return jdbc.queryForObject("SELECT subscription_rows,target_owners,daily_runs,preview_identities,"
                +"(SELECT COUNT(*) FROM news_subscription),(SELECT COUNT(*) FROM news_delivery_settings),"
                +"(SELECT COUNT(*) FROM news_daily_run),(SELECT COUNT(*) FROM news_content_identity WHERE identity_kind<>'SCHEDULED') "
                +"FROM news_control_capacity WHERE id=1",(r,i)->{
                    Map<String,Long> used=Map.of("subscriptions",r.getLong(1),"targetOwners",r.getLong(2),"dailyRuns",r.getLong(3),"previewIdentities",r.getLong(4));
                    Map<String,Long> measured=Map.of("subscriptions",r.getLong(5),"targetOwners",r.getLong(6),"dailyRuns",r.getLong(7),"previewIdentities",r.getLong(8));
                    return Map.of("scope","NEWS_CONTROL_IDENTITIES","used",used,"measured",measured,"accountingMatches",used.equals(measured),"limits",limits);
                });
    }
    private boolean existingCurrent(long owner,String key,String requestHash) {
        var values=jdbc.queryForList("SELECT request_hash FROM news_content_identity WHERE owner_id=? AND idempotency_hash=? FOR UPDATE",String.class,owner,key);
        if(values.isEmpty())return false;
        if(!values.getFirst().equals(requestHash))throw new NewsException(ErrorCode.NEWS_004);
        return true;
    }
    private void requireTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new NewsException(ErrorCode.NEWS_009);}
}
