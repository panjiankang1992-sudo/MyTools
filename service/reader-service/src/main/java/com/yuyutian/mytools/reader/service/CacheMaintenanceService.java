package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CacheMaintenanceBatchResult;
import com.yuyutian.mytools.reader.model.CacheMaintenanceRecord;
import com.yuyutian.mytools.reader.model.CacheMaintenanceView;
import com.yuyutian.mytools.reader.model.CreateCacheMaintenanceRequest;
import com.yuyutian.mytools.reader.repository.CacheMaintenanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 章节缓存维护任务编排服务。
 */
@Service
public class CacheMaintenanceService {

    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
    private final CacheMaintenanceRepository repository;
    private final TaskSchedulerClient schedulerClient;

    /**
     * 创建章节缓存维护服务。
     */
    public CacheMaintenanceService(CacheMaintenanceRepository repository, TaskSchedulerClient schedulerClient) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 幂等创建章节缓存维护任务。
     */
    @Transactional
    public CacheMaintenanceView create(CreateCacheMaintenanceRequest request) {
        if (normalizedCutoff(request.cutoffAt()).isAfter(Instant.now())) {
            throw new CacheMaintenanceConflictException();
        }
        CacheMaintenanceRecord record = repository.findByKey(request.idempotencyKey())
                .orElseGet(() -> insert(request));
        if (!equivalent(record, request)) {
            throw new CacheMaintenanceConflictException();
        }
        if (record.taskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_cleanup_chapter_cache",
                    "reader_cleanup_chapter_cache:" + record.id() + ":v1", "READER_CACHE_MAINTENANCE",
                    record.id(), 20, Map.of("maintenanceId", record.id().toString()));
            repository.bindTask(record.id(), taskId);
            record = required(record.id());
        }
        return view(record);
    }

    /**
     * 查询章节缓存维护任务。
     */
    public CacheMaintenanceView get(UUID id) {
        return view(required(id));
    }

    /**
     * 执行一个受限删除批次。
     */
    @Transactional
    public CacheMaintenanceBatchResult deleteBatch(UUID id) {
        CacheMaintenanceRecord record = required(id);
        if (TERMINAL.contains(record.status())) {
            return new CacheMaintenanceBatchResult(0, record.deletedCount(), record.status());
        }
        int deleted = repository.deleteBatch(record);
        CacheMaintenanceRecord updated = required(id);
        return new CacheMaintenanceBatchResult(deleted, updated.deletedCount(), updated.status());
    }

    /**
     * 幂等设置维护任务终态。
     */
    @Transactional
    public CacheMaintenanceView finish(UUID id, String status, String errorCode) {
        if (!TERMINAL.contains(status)) {
            throw new CacheMaintenanceConflictException();
        }
        CacheMaintenanceRecord current = required(id);
        if (TERMINAL.contains(current.status()) && !current.status().equals(status)) {
            throw new CacheMaintenanceConflictException();
        }
        repository.finish(id, status, errorCode);
        return view(required(id));
    }

    private CacheMaintenanceRecord insert(CreateCacheMaintenanceRequest request) {
        Instant now = Instant.now();
        CacheMaintenanceRecord record = new CacheMaintenanceRecord(UUID.randomUUID(), request.idempotencyKey(),
                request.maintenanceType(), normalizedCutoff(request.cutoffAt()), request.batchSize(), "ACCEPTED", null,
                0, null, now, now);
        repository.insert(record);
        return record;
    }

    private CacheMaintenanceRecord required(UUID id) {
        return repository.findById(id).orElseThrow(CacheMaintenanceNotFoundException::new);
    }

    private boolean equivalent(CacheMaintenanceRecord record, CreateCacheMaintenanceRequest request) {
        return record.maintenanceType().equals(request.maintenanceType())
                && record.cutoffAt().equals(normalizedCutoff(request.cutoffAt()))
                && record.batchSize() == request.batchSize();
    }

    private CacheMaintenanceView view(CacheMaintenanceRecord record) {
        return new CacheMaintenanceView(record.id(), record.maintenanceType(), record.cutoffAt(),
                record.batchSize(), record.status(), record.taskId(), record.deletedCount(), record.lastErrorCode(),
                record.createdAt(), record.updatedAt());
    }

    private Instant normalizedCutoff(Instant cutoff) {
        return cutoff.truncatedTo(ChronoUnit.MICROS);
    }
}
