package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateLibraryRebuildRequest;
import com.yuyutian.mytools.reader.model.LibraryIndexEntryView;
import com.yuyutian.mytools.reader.model.LibraryRebuildBatchResult;
import com.yuyutian.mytools.reader.model.LibraryRebuildRecord;
import com.yuyutian.mytools.reader.model.LibraryRebuildView;
import com.yuyutian.mytools.reader.repository.LibraryRebuildRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冻结快照式书库索引重建服务。
 */
@Service
public class LibraryRebuildService {

    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
    private final LibraryRebuildRepository repository;
    private final TaskSchedulerClient schedulerClient;

    /**
     * 创建书库索引重建服务。
     */
    public LibraryRebuildService(LibraryRebuildRepository repository, TaskSchedulerClient schedulerClient) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 幂等创建书库索引重建任务。
     */
    @Transactional
    public LibraryRebuildView create(CreateLibraryRebuildRequest request) {
        Instant snapshot = normalize(request.snapshotAt());
        if (snapshot.isAfter(Instant.now())) {
            throw new LibraryRebuildConflictException();
        }
        LibraryRebuildRecord record = repository.findByKey(request.ownerId(), request.idempotencyKey())
                .orElseGet(() -> insert(request, snapshot));
        if (record.ownerId() != request.ownerId() || !record.snapshotAt().equals(snapshot)
                || record.batchSize() != request.batchSize()) {
            throw new LibraryRebuildConflictException();
        }
        if (record.taskId() == null) {
            UUID taskId = schedulerClient.createTask("reader_reindex_library",
                    "reader_reindex_library:" + record.id() + ":v1", "READER_LIBRARY_REBUILD",
                    record.id(), 20, Map.of("rebuildId", record.id().toString()));
            repository.bindTask(record.id(), taskId);
            record = required(record.id());
        }
        return view(record);
    }

    /**
     * 查询书库索引重建任务。
     */
    public LibraryRebuildView get(UUID id) {
        return view(required(id));
    }

    /**
     * 写入一个冻结快照批次。
     */
    @Transactional
    public LibraryRebuildBatchResult rebuildBatch(UUID id) {
        LibraryRebuildRecord record = required(id);
        if (TERMINAL.contains(record.status())) {
            return new LibraryRebuildBatchResult(0, record.indexedCount(), record.lastCursor(), true);
        }
        return repository.rebuildBatch(record);
    }

    /**
     * 原子发布已完整处理的 generation。
     */
    @Transactional
    public LibraryRebuildView publish(UUID id) {
        LibraryRebuildRecord record = required(id);
        if ("SUCCEEDED".equals(record.status())) {
            return view(record);
        }
        if (TERMINAL.contains(record.status())) {
            throw new LibraryRebuildConflictException();
        }
        try {
            repository.publish(id);
        } catch (IllegalStateException exception) {
            throw new LibraryRebuildConflictException();
        }
        return view(required(id));
    }

    /**
     * 设置失败、超时或取消终态。
     */
    @Transactional
    public LibraryRebuildView finish(UUID id, String status, String errorCode) {
        if (!Set.of("FAILED", "TIMED_OUT", "CANCELLED").contains(status)) {
            throw new LibraryRebuildConflictException();
        }
        LibraryRebuildRecord record = required(id);
        if (TERMINAL.contains(record.status()) && !record.status().equals(status)) {
            throw new LibraryRebuildConflictException();
        }
        repository.finish(id, status, errorCode);
        return view(required(id));
    }

    /**
     * 查询当前原子发布的可再生书库索引。
     */
    public List<LibraryIndexEntryView> activeIndex(long ownerId) {
        return repository.activeIndex(ownerId);
    }

    private LibraryRebuildRecord insert(CreateLibraryRebuildRequest request, Instant snapshot) {
        Instant now = Instant.now();
        LibraryRebuildRecord record = new LibraryRebuildRecord(UUID.randomUUID(), request.ownerId(),
                request.idempotencyKey(), snapshot, request.batchSize(), "ACCEPTED", null, 0,
                null, null, now, now);
        repository.insert(record);
        return record;
    }

    private LibraryRebuildRecord required(UUID id) {
        return repository.findById(id).orElseThrow(LibraryRebuildNotFoundException::new);
    }

    private Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private LibraryRebuildView view(LibraryRebuildRecord record) {
        return new LibraryRebuildView(record.id(), record.ownerId(), record.snapshotAt(), record.batchSize(),
                record.status(), record.taskId(), record.indexedCount(), record.lastCursor(),
                record.lastErrorCode(), record.createdAt(), record.updatedAt());
    }
}
