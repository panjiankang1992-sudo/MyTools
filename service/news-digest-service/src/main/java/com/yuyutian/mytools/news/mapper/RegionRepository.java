package com.yuyutian.mytools.news.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.RegionSnapshot;
import com.yuyutian.mytools.news.Model.RegionModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 保存只增不改的节点观测和来源包，并在生成前冻结每个幂等请求的地区。 */
@Repository
public class RegionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ControlCapacityRepository capacity;
    private final TransactionTemplate tx;
    private final RegistryCapacityRepository registry;

    /** 创建地区仓储。 */
    public RegionRepository(JdbcTemplate jdbc, ObjectMapper json, ControlCapacityRepository capacity, TransactionTemplate tx, RegistryCapacityRepository registry) {
        this.jdbc = jdbc; this.json = json; this.capacity = capacity; this.tx = tx; this.registry=registry;
    }

    /** 查询原始报告，只供内部去重判断。 */
    public Optional<RegionObservation> observation(UUID id) {
        return jdbc.query("SELECT payload_json FROM news_region_observation WHERE id = ?",
                (row, index) -> decode(row.getString(1), RegionObservation.class), id.toString()).stream().findFirst();
    }

    /** 同一报告标识只能绑定同一份内容，响应丢失后可安全重放。 */
    public void saveObservation(RegionObservation report, Instant receivedAt) { saveObservation(report,receivedAt,null); }

    /** 作业报告使用创建作业时的预留，必须与同一节点和报告身份绑定。 */
    public void saveObservation(RegionObservation report,Instant receivedAt,UUID jobId) {
        tx.executeWithoutResult(status->{
            if(jobId==null)registry.lock();
            else {
                // 已接纳任务只锁自己的作业，不反向申请全局登记锁。
                var jobs=jdbc.query("SELECT node_id,observation_id,status FROM news_region_job WHERE id=? FOR UPDATE",
                        (row,index)->new JobIdentity(row.getString(1),row.getString(2),row.getString(3)),jobId.toString());
                if(jobs.isEmpty() || !jobs.getFirst().node().equals(report.nodeId())
                        || !java.util.Set.of("QUEUED","DISPATCHED","STORED").contains(jobs.getFirst().status())
                        || jobs.getFirst().observation()!=null && !jobs.getFirst().observation().equals(report.id().toString()))
                    throw new NewsException(ErrorCode.NEWS_016);
            }
            var existing=jdbc.query("SELECT payload_json,region_job_id FROM news_region_observation WHERE id=? FOR UPDATE",
                    (row,index)->new StoredObservation(decode(row.getString(1),RegionObservation.class),row.getString(2)),report.id().toString());
            if(!existing.isEmpty()) {
                if(!existing.getFirst().report().equals(report) || !java.util.Objects.equals(existing.getFirst().job(),jobId==null?null:jobId.toString()))
                    throw new NewsException(ErrorCode.NEWS_004);
                return;
            }
            String payload=encode(report);RegistryCapacityRepository.boundedPayload(payload,true);
            if(jobId==null)registry.reserve(0,0,0,0,1);
            try {
                jdbc.update("INSERT INTO news_region_observation(id,node_id,observed_ms,received_ms,status,fingerprint,payload_hash,payload_json,region_job_id,capacity_kind) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        report.id().toString(),report.nodeId(),report.observedAt().toEpochMilli(),receivedAt.toEpochMilli(),report.status().name(),report.egressFingerprint(),
                        ContentIdentity.hash(payload),payload,jobId==null?null:jobId.toString(),jobId==null?"DIRECT":"JOB");
            } catch(DuplicateKeyException exception) {
                // 同一作业不能通过换报告标识占用第二个观测名额。
                throw new NewsException(ErrorCode.NEWS_004);
            }
        });
    }

    private record StoredObservation(RegionObservation report,String job) { }
    private record JobIdentity(String node,String observation,String status) { }

    /** 仅查询截止前收到且已经发生的最新观测，后到报告不能回填历史。 */
    public Optional<RegionObservation> latest(String nodeId, Instant cutoff) {
        return jdbc.query("SELECT payload_json FROM news_region_observation WHERE node_id = ? AND observed_ms <= ? AND received_ms <= ? "
                        + "ORDER BY observed_ms DESC, received_ms DESC, id DESC LIMIT 1", (row, index) -> decode(row.getString(1), RegionObservation.class),
                nodeId, cutoff.toEpochMilli(), cutoff.toEpochMilli()).stream().findFirst();
    }

    /** 缓存只能来自同节点同出口，且不能跨过一次出口变化或未知出口观测。 */
    public Optional<RegionObservation> cached(RegionObservation current, Instant cutoff) {
        return jdbc.query("SELECT a.payload_json FROM news_region_observation a WHERE a.node_id = ? AND a.status = 'RESOLVED' "
                        + "AND a.fingerprint = ? AND a.observed_ms >= ? AND a.observed_ms <= ? AND a.received_ms <= ? "
                        + "AND NOT EXISTS (SELECT 1 FROM news_region_observation b WHERE b.node_id = a.node_id "
                        + "AND b.observed_ms > a.observed_ms AND b.observed_ms <= ? AND b.received_ms <= ? "
                        + "AND (b.fingerprint IS NULL OR b.fingerprint <> a.fingerprint)) "
                        + "ORDER BY a.observed_ms DESC, a.received_ms DESC, a.id DESC LIMIT 1",
                (row, index) -> decode(row.getString(1), RegionObservation.class), current.nodeId(), current.egressFingerprint(),
                cutoff.minus(Duration.ofDays(7)).toEpochMilli(), current.observedAt().toEpochMilli(), cutoff.toEpochMilli(),
                current.observedAt().toEpochMilli(), cutoff.toEpochMilli()).stream().findFirst();
    }

    /** 登记不可变地区来源版本，重复同内容登记不会改变生效时间。 */
    public RegionSourcePack savePack(RegionSourcePack pack, Instant now) {
        return tx.execute(status->{
            registry.lock();
            var existing=jdbc.query("SELECT payload_json FROM news_region_source_pack WHERE region_code=? AND version_number=? FOR UPDATE",
                    (row,index)->decode(row.getString(1),RegionSourcePack.class),pack.regionCode(),pack.version());
            if(!existing.isEmpty()) {
                if(!pack.equals(existing.getFirst()))throw new NewsException(ErrorCode.NEWS_004);
                return existing.getFirst();
            }
            String payload=encode(pack);RegistryCapacityRepository.boundedPayload(payload,false);
            registry.reserve(0,1,0,0,0);
            jdbc.update("INSERT INTO news_region_source_pack(region_code,version_number,registered_ms,payload_json,capacity_recorded) VALUES(?,?,?,?,TRUE)",pack.regionCode(),pack.version(),now.toEpochMilli(),payload);
            return pack;
        });
    }

    /** 取得截止时刻已登记的最高版本，不把之后补录的来源包带入旧日报。 */
    public Optional<RegionSourcePack> pack(String code, Instant cutoff) {
        return jdbc.query("SELECT payload_json FROM news_region_source_pack WHERE region_code = ? AND registered_ms <= ? "
                        + "ORDER BY version_number DESC LIMIT 1", (row, index) -> decode(row.getString(1), RegionSourcePack.class),
                code, cutoff.toEpochMilli()).stream().findFirst();
    }

    /** 管理端查看已登记版本。 */
    public List<RegionSourcePack> packs() {
        return jdbc.query("SELECT payload_json FROM news_region_source_pack ORDER BY region_code, version_number DESC",
                (row, index) -> decode(row.getString(1), RegionSourcePack.class));
    }

    /** 即使后续生成失败，也保持同一所有者幂等请求已经绑定的地区。 */
    public RegionSnapshot bind(long ownerId, String key, String requestHash, Instant cutoff, Instant now, Supplier<RegionSnapshot> resolver) {
        return tx.execute(status -> {
            capacity.admitContent(ownerId,key,requestHash);
            return bindLocked(ownerId,key,requestHash,cutoff,now,resolver);
        });
    }

    private RegionSnapshot bindLocked(long ownerId, String key, String requestHash, Instant cutoff, Instant now, Supplier<RegionSnapshot> resolver) {
        var existing = bound(ownerId, key, requestHash);
        if (existing.isPresent()) return existing.orElseThrow();
        RegionSnapshot snapshot = resolver.get();
        try {
            jdbc.update("INSERT INTO news_region_snapshot(owner_id, idempotency_hash, request_hash, cutoff_ms, created_ms, payload_json) VALUES (?, ?, ?, ?, ?, ?)",
                    ownerId, ContentIdentity.hash(key), requestHash, cutoff.toEpochMilli(), now.toEpochMilli(), encode(snapshot));
            return snapshot;
        } catch (DuplicateKeyException exception) {
            // 并发生成只接受首次绑定的地区，失败重试或切换节点不能漂移。
            return bound(ownerId, key, requestHash).orElseThrow(() -> new NewsException(ErrorCode.NEWS_009));
        }
    }

    private Optional<RegionSnapshot> bound(long ownerId, String key, String hash) {
        return jdbc.query("SELECT request_hash, payload_json FROM news_region_snapshot WHERE owner_id = ? AND idempotency_hash = ? FOR UPDATE", (row, index) -> {
            if (!hash.equals(row.getString(1))) throw new NewsException(ErrorCode.NEWS_004);
            return decode(row.getString(2), RegionSnapshot.class);
        }, ownerId, ContentIdentity.hash(key)).stream().findFirst();
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }
}
