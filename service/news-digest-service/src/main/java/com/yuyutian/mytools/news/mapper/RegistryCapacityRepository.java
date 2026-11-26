package com.yuyutian.mytools.news.mapper;

import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.RegistryCapacityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 新登记与额度在同一事务提交，完成已预留地区任务不需要再次申请全局额度。 */
@Repository
public class RegistryCapacityRepository {
    private final JdbcTemplate jdbc;
    private final RegistryCapacityProperties limits;
    /** 注入持久登记账本及当前实例额度。 */
    public RegistryCapacityRepository(JdbcTemplate jdbc,RegistryCapacityProperties limits) {this.jdbc=jdbc;this.limits=limits;}
    /** 必须在锁定来源、节点、来源包或独立报告之前调用。 */
    public void lock() {
        requireTransaction();jdbc.queryForObject("SELECT id FROM news_registry_capacity WHERE id=1 FOR UPDATE",Integer.class);
    }
    /** 为新身份预留额度，取消或内容清理不归还身份。 */
    public void reserve(long sources,long packs,long nodes,long jobs,long observations) {
        requireTransaction();
        long[] used=jdbc.queryForObject("SELECT source_rows,pack_rows,node_rows,region_jobs,direct_observations FROM news_registry_capacity WHERE id=1 FOR UPDATE",
                (row,index)->new long[]{row.getLong(1),row.getLong(2),row.getLong(3),row.getLong(4),row.getLong(5)});
        if(used==null)throw new NewsException(ErrorCode.NEWS_009);
        long[] delta={sources,packs,nodes,jobs,observations};long[] maximum={limits.sources(),limits.sourcePacks(),limits.regionNodes(),limits.regionJobs(),limits.directObservations()};
        for(int index=0;index<used.length;index++) {
            // 存量超出新额度时只阻止新准入，不改变已接纳身份及其报告预留。
            if(delta[index]<0)throw new NewsException(ErrorCode.NEWS_009);
            used[index]=Math.addExact(used[index],delta[index]);
            if(delta[index]>0 && used[index]>maximum[index])throw new NewsException(ErrorCode.NEWS_030);
        }
        jdbc.update("UPDATE news_registry_capacity SET source_rows=?,pack_rows=?,node_rows=?,region_jobs=?,direct_observations=? WHERE id=1",used[0],used[1],used[2],used[3],used[4]);
    }
    /** 新登记也在仓储边界限制序列化大小，不能用一个长文本绕过行数预算。 */
    public static void boundedPayload(String payload,boolean observation) {
        if(payload.getBytes(StandardCharsets.UTF_8).length>(observation?8192:32768))throw new NewsException(ErrorCode.NEWS_002);
    }
    /** 在同一个数据库快照比较账本和实际身份，不输出来源内容或出口指纹。 */
    public Map<String,Object> status() {
        return jdbc.queryForObject("SELECT source_rows,pack_rows,node_rows,region_jobs,direct_observations,"
                +"(SELECT COUNT(*) FROM news_source),(SELECT COUNT(*) FROM news_region_source_pack),"
                +"(SELECT COUNT(*) FROM news_region_schedule),(SELECT COUNT(*) FROM news_region_job),"
                +"(SELECT COUNT(*) FROM news_region_observation WHERE capacity_kind='DIRECT') FROM news_registry_capacity WHERE id=1",
                (row,index)->{
                    Map<String,Long> used=Map.of("sources",row.getLong(1),"sourcePacks",row.getLong(2),"regionNodes",row.getLong(3),"regionJobs",row.getLong(4),"directObservations",row.getLong(5));
                    Map<String,Long> measured=Map.of("sources",row.getLong(6),"sourcePacks",row.getLong(7),"regionNodes",row.getLong(8),"regionJobs",row.getLong(9),"directObservations",row.getLong(10));
                    return Map.of("scope","SOURCE_REGION_REGISTRY","used",used,"measured",measured,"accountingMatches",used.equals(measured),"limits",limits);
                });
    }
    private void requireTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new NewsException(ErrorCode.NEWS_009);}
}
