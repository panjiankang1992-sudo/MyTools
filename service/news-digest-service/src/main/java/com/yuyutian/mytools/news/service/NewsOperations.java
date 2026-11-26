package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.config.*;
import com.yuyutian.mytools.news.mapper.RetentionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只暴露聚合计数和时间，不把所有者、目标、正文或凭据作为指标标签。 */
@Service
public final class NewsOperations {
    private final JdbcTemplate jdbc;
    private final RetentionRepository retention;
    private final RetentionProperties policy;
    private final Clock clock;
    private final com.yuyutian.mytools.news.mapper.ControlCapacityRepository control;
    private final com.yuyutian.mytools.news.mapper.RegistryCapacityRepository catalog;
    private Map<String,Long> cached=Map.of();
    private long cachedAt=Long.MIN_VALUE;
    private boolean cachedFailure;
    /** 注册固定指标集合，抓取使用三十秒聚合缓存。 */
    public NewsOperations(JdbcTemplate jdbc,RetentionRepository retention,RetentionProperties policy,Clock clock,MeterRegistry registry,CollectionCapacityProperties capacity,com.yuyutian.mytools.news.mapper.ControlCapacityRepository control,ControlCapacityProperties controlLimits,com.yuyutian.mytools.news.mapper.RegistryCapacityRepository catalog,RegistryCapacityProperties registryLimits) {
        this.jdbc=jdbc;this.retention=retention;this.policy=policy;this.clock=clock;
        this.control=control;this.catalog=catalog;
        Gauge.builder("news.registry.capacity.source.limit",registryLimits,RegistryCapacityProperties::sources).register(registry);
        Gauge.builder("news.registry.capacity.pack.limit",registryLimits,RegistryCapacityProperties::sourcePacks).register(registry);
        Gauge.builder("news.registry.capacity.node.limit",registryLimits,RegistryCapacityProperties::regionNodes).register(registry);
        Gauge.builder("news.registry.capacity.job.limit",registryLimits,RegistryCapacityProperties::regionJobs).register(registry);
        Gauge.builder("news.registry.capacity.observation.limit",registryLimits,RegistryCapacityProperties::directObservations).register(registry);
        for(String name:new String[]{"source_rows","pack_rows","node_rows","region_jobs","direct_observations"}) {
            Gauge.builder("news.registry.capacity."+name,this,value->value.gauge("registry_"+name)).register(registry);
        }
        Gauge.builder("news.retention.enabled",policy,value->value.enabled()?1:0).register(registry);
        Gauge.builder("news.operations.database_available",this,value->value.available()).register(registry);
        Gauge.builder("news.collection.capacity.article.limit",capacity,CollectionCapacityProperties::articleRows).register(registry);
        Gauge.builder("news.collection.capacity.snapshot.limit",capacity,CollectionCapacityProperties::snapshotRows).register(registry);
        Gauge.builder("news.collection.capacity.withdrawal.limit",capacity,CollectionCapacityProperties::withdrawalRows).register(registry);
        Gauge.builder("news.collection.capacity.payload.limit",capacity,CollectionCapacityProperties::payloadBytes).register(registry);
        Gauge.builder("news.control.capacity.subscription.limit",controlLimits,ControlCapacityProperties::subscriptions).register(registry);
        Gauge.builder("news.control.capacity.target.limit",controlLimits,ControlCapacityProperties::targetOwners).register(registry);
        Gauge.builder("news.control.capacity.run.limit",controlLimits,ControlCapacityProperties::dailyRuns).register(registry);
        Gauge.builder("news.control.capacity.preview.limit",controlLimits,ControlCapacityProperties::previewIdentities).register(registry);
        for(String name:new String[]{"subscription_rows","target_owners","daily_runs","preview_identities"}) {
            Gauge.builder("news.control.capacity."+name,this,value->value.gauge("control_"+name)).register(registry);
        }
        for(String name:new String[]{"article_rows","snapshot_rows","withdrawal_rows","payload_bytes"}) {
            Gauge.builder("news.collection.capacity."+name,this,value->value.gauge("capacity_"+name)).register(registry);
        }
        for(String name:new String[]{"retention_failed","sources_enabled","sources_failed","collection_overdue","task_dead","generation_pending","delivery_unknown","delivery_rejected","delivery_pending","delivery_oldest_pending_seconds","retention_oldest_article_evidence_due_seconds"}) {
            Gauge.builder("news.operations."+name,this,value->value.gauge(name)).register(registry);
        }
    }
    /** 管理接口总是重新核对数据库，不能把旧缓存当成实时成功。 */
    public Map<String,Object> status() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("observedAt",clock.instant());result.put("retentionEnabled",policy.enabled());
        result.put("counts",counts(true));result.put("retentionDue",retention.preview(clock.instant()));
        result.put("retentionCheckpoint",retention.checkpoint());
        result.put("collectionCapacity",retention.capacityStatus());
        result.put("controlCapacity",control.status());
        result.put("registryCapacity",catalog.status());
        result.put("retentionPolicy",Map.of("evidenceHours",policy.evidenceHours(),"metadataDays",policy.metadataDays(),"editionDays",policy.editionDays(),"deliveryDays",policy.deliveryDays(),"batchSize",policy.batchSize()));
        return result;
    }
    private double available(){try{counts(false);return 1;}catch(RuntimeException e){return 0;}}
    private double gauge(String name){try{return counts(false).getOrDefault(name,0L);}catch(RuntimeException e){return Double.NaN;}}
    private synchronized Map<String,Long> counts(boolean force) {
        long now=clock.instant().toEpochMilli();
        if(!force && cachedAt!=Long.MIN_VALUE && now>=cachedAt && now-cachedAt<30_000) {
            if(cachedFailure)throw new com.yuyutian.mytools.news.common.NewsException(com.yuyutian.mytools.news.common.ErrorCode.NEWS_009);
            return cached;
        }
        try {
        Map<String,Long> values=new LinkedHashMap<>();
        values.put("retention_failed",count("SELECT COUNT(*) FROM news_retention_state WHERE last_status='FAILED'"));
        values.put("sources_enabled",count("SELECT COUNT(*) FROM news_source WHERE enabled=TRUE"));
        values.put("sources_failed",count("SELECT COUNT(*) FROM news_source WHERE enabled=TRUE AND health IN ('FETCH_FAILED','RATE_LIMITED','STORAGE_FULL')"));
        values.put("capacity_article_rows",count("SELECT article_rows FROM news_collection_capacity WHERE id=1"));
        values.put("capacity_snapshot_rows",count("SELECT snapshot_rows FROM news_collection_capacity WHERE id=1"));
        values.put("capacity_withdrawal_rows",count("SELECT withdrawal_rows FROM news_collection_capacity WHERE id=1"));
        values.put("capacity_payload_bytes",count("SELECT payload_bytes FROM news_collection_capacity WHERE id=1"));
        values.put("control_subscription_rows",count("SELECT subscription_rows FROM news_control_capacity WHERE id=1"));
        values.put("control_target_owners",count("SELECT target_owners FROM news_control_capacity WHERE id=1"));
        values.put("control_daily_runs",count("SELECT daily_runs FROM news_control_capacity WHERE id=1"));
        values.put("control_preview_identities",count("SELECT preview_identities FROM news_control_capacity WHERE id=1"));
        for(String name:new String[]{"source_rows","pack_rows","node_rows","region_jobs","direct_observations"}) {
            // 列名来自固定常量集合，不能由请求决定 SQL 标识符。
            values.put("registry_"+name,count("SELECT "+name+" FROM news_registry_capacity WHERE id=1"));
        }
        values.put("collection_overdue",count("SELECT COUNT(*) FROM news_source_poll p JOIN news_source s ON s.id=p.source_id WHERE s.enabled=TRUE AND p.next_due_ms<?",now-3600_000));
        values.put("task_dead",count("SELECT COUNT(*) FROM news_task_outbox WHERE state='DEAD'"));
        values.put("generation_pending",count("SELECT COUNT(*) FROM news_daily_run WHERE status IN ('QUEUED','DISPATCHED')"));
        values.put("delivery_unknown",count("SELECT COUNT(*) FROM news_dispatch_outbox WHERE status='UNKNOWN'"));
        values.put("delivery_rejected",count("SELECT COUNT(*) FROM news_dispatch_outbox WHERE status='REJECTED'"));
        values.put("delivery_pending",count("SELECT COUNT(*) FROM news_dispatch_outbox WHERE status IN ('PENDING','SUBMITTING','ACCEPTED','SENDING') OR retry_generation IS NOT NULL"));
        Long oldest=jdbc.queryForObject("SELECT MIN(updated_ms) FROM news_dispatch_outbox WHERE status IN ('PENDING','SUBMITTING','ACCEPTED','SENDING')",Long.class);
        values.put("delivery_oldest_pending_seconds",oldest==null?0:Math.max(0,(now-oldest)/1000));
        Long article=jdbc.queryForObject("SELECT MIN(a.fetched_ms+3600000*CASE WHEN p.evidence_hours<? THEN p.evidence_hours ELSE ? END) FROM news_article a LEFT JOIN news_source_retention p ON p.source_id=a.source_id WHERE a.evidence_expired_ms IS NULL",Long.class,policy.evidenceHours(),policy.evidenceHours());
        values.put("retention_oldest_article_evidence_due_seconds",article==null?0:Math.max(0,(now-article)/1000));
        cached=Map.copyOf(values);cachedAt=now;cachedFailure=false;return cached;
        } catch(RuntimeException exception) {
            // 数据库失败也缓存三十秒，避免一次抓取对每个指标重复等待连接超时。
            cachedAt=now;cachedFailure=true;
            throw new com.yuyutian.mytools.news.common.NewsException(com.yuyutian.mytools.news.common.ErrorCode.NEWS_009);
        }
    }
    private long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);return value==null?0:value;}
}
