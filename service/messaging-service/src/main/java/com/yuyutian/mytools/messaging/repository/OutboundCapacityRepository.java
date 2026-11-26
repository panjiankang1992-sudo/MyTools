package com.yuyutian.mytools.messaging.repository;

import com.yuyutian.mytools.messaging.config.OutboundCapacityProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.service.OutboundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 以数据库行锁串行化新准入和正文释放，计费随业务事务提交或回滚。 */
@Repository
public class OutboundCapacityRepository {
    private final JdbcTemplate jdbc;
    private final OutboundCapacityProperties limits;
    /** 注入持久计费账本及当前实例额度。 */
    public OutboundCapacityRepository(JdbcTemplate jdbc, OutboundCapacityProperties limits) {
        this.jdbc=jdbc;this.limits=limits;
    }
    /** 在锁定目的地和投递之前获取容量锁，外部调用期间不持锁。 */
    public void lock() {
        requireTransaction();
        jdbc.queryForObject("SELECT id FROM messaging_outbound_capacity WHERE id=1 FOR UPDATE",Integer.class);
    }
    /** 仅新身份预留行额度，已接纳请求的执行和重试不再申请额度。 */
    public void reserve(long destinations,long deliveries,long cancellations,long bytes) {
        if(destinations<0 || deliveries<0 || cancellations<0 || bytes<0)fail();
        adjust(destinations,deliveries,cancellations,bytes);
    }
    /** 正文清理只归还字节，永远不释放已占用的幂等身份。 */
    public void releaseBytes(long bytes) {
        if(bytes<0)fail();
        adjust(0,0,0,-bytes);
    }
    /** 与数据库 OCTET_LENGTH 一致地计算冻结正文大小。 */
    public static long payloadBytes(String content) { return content.getBytes(StandardCharsets.UTF_8).length; }
    /** 同一数据库快照比较账本和明细，绝不通过自动重置掩盖差异。 */
    public Map<String,Object> status() {
        return jdbc.queryForObject("SELECT destination_rows,delivery_rows,cancel_only_rows,payload_bytes,"
                +"(SELECT COUNT(*) FROM messaging_destination),"
                +"(SELECT COUNT(*) FROM messaging_outbound WHERE capacity_kind='DELIVERY'),"
                +"(SELECT COUNT(*) FROM messaging_outbound WHERE capacity_kind='CANCEL_ONLY'),"
                +"(SELECT COALESCE(SUM(capacity_bytes),0) FROM messaging_outbound) "
                +"FROM messaging_outbound_capacity WHERE id=1",(row,index)->{
                    Map<String,Long> used=Map.of("destinationRows",row.getLong(1),"deliveryRows",row.getLong(2),
                            "cancelOnlyRows",row.getLong(3),"payloadBytes",row.getLong(4));
                    Map<String,Long> measured=Map.of("destinationRows",row.getLong(5),"deliveryRows",row.getLong(6),
                            "cancelOnlyRows",row.getLong(7),"payloadBytes",row.getLong(8));
                    return Map.of("scope","OUTBOUND_V2","used",used,"measured",measured,"accountingMatches",used.equals(measured),"limits",limits);
                });
    }
    private void adjust(long destinations,long deliveries,long cancellations,long bytes) {
        requireTransaction();
        long[] used=jdbc.queryForObject("SELECT destination_rows,delivery_rows,cancel_only_rows,payload_bytes FROM messaging_outbound_capacity WHERE id=1 FOR UPDATE",
                (row,index)->new long[]{row.getLong(1),row.getLong(2),row.getLong(3),row.getLong(4)});
        if(used==null)fail();
        long[] delta={destinations,deliveries,cancellations,bytes};
        long[] maximum={limits.destinationRows(),limits.deliveryRows(),limits.cancelOnlyRows(),limits.payloadBytes()};
        for(int index=0;index<delta.length;index++) {
            // 降低配置后仍允许释放正文，负数账本必须回滚并人工调查。
            used[index]=Math.addExact(used[index],delta[index]);
            if(used[index]<0)fail();
            if(delta[index]>0 && used[index]>maximum[index])throw new OutboundException(ErrorCode.OUTBOUND_CAPACITY_EXCEEDED);
        }
        jdbc.update("UPDATE messaging_outbound_capacity SET destination_rows=?,delivery_rows=?,cancel_only_rows=?,payload_bytes=? WHERE id=1",used[0],used[1],used[2],used[3]);
    }
    private void requireTransaction() { if(!TransactionSynchronizationManager.isActualTransactionActive())fail(); }
    private void fail() { throw new OutboundException(ErrorCode.OUTBOUND_CAPACITY_INVALID); }
}
