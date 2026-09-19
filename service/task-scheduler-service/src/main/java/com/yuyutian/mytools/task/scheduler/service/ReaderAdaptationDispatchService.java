package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.ReaderAdaptationDispatchView;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Reader 业务键派发与取消屏障，解决创建响应丢失和取消先到达的竞态。 */
@Service
public class ReaderAdaptationDispatchService {
    private final ReaderAdaptationTaskGuard guard;
    private final TaskInstanceService instances;
    private final TaskInstanceRepository repository;
    private final JdbcTemplate jdbc;

    /** 注入任务实例服务，复用既有事件与取消传播，不直接覆盖调度状态。 */
    public ReaderAdaptationDispatchService(ReaderAdaptationTaskGuard guard, TaskInstanceService instances,
                                           TaskInstanceRepository repository, JdbcTemplate jdbc) {
        this.guard = guard;
        this.instances = instances;
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** 提交只有固定业务 ID 的幂等任务，不接收模型或正文参数。 */
    @Transactional
    public ReaderAdaptationDispatchView submit(UUID adaptationId) {
        guard.requireReaderClient();
        return view(adaptationId, instances.create(guard.request(adaptationId)), false);
    }

    /** 只查询已存在任务及取消屏障，不通过查询产生新的任务。 */
    @Transactional(readOnly = true)
    public ReaderAdaptationDispatchView find(UUID adaptationId) {
        guard.requireReaderClient();
        // 屏障与任务状态取自单条查询，不能在 READ_COMMITTED 下拼出“旧的无任务 + 新的已取消”。
        var snapshots = jdbc.query("""
                SELECT g.task_instance_id, g.cancel_requested_at, t.status FROM reader_adaptation_dispatch_guard g
                LEFT JOIN task_instance t ON t.id = g.task_instance_id WHERE g.adaptation_id = ?
                """, (row, index) -> new ReaderAdaptationDispatchView(adaptationId,
                row.getString("task_instance_id") == null ? null : UUID.fromString(row.getString("task_instance_id")),
                row.getTimestamp("cancel_requested_at") != null, row.getString("status")), adaptationId.toString());
        if (snapshots.isEmpty()) {
            return view(adaptationId, null, false);
        }
        var snapshot = snapshots.getFirst();
        if (snapshot.taskInstanceId() != null) {
            guard.requireTask(adaptationId, repository.findById(snapshot.taskInstanceId()).orElseThrow());
        }
        return snapshot;
    }

    /** 与创建在同一业务键上线性化取消；无任务也保留屏障以拒绝所有迟到提交。 */
    @Transactional
    public ReaderAdaptationDispatchView cancel(UUID adaptationId) {
        guard.requireReaderClient();
        guard.lock(adaptationId);
        guard.recordCancellation(adaptationId);
        TaskInstanceView task = repository.findByIdempotencyKey(ReaderAdaptationTaskGuard.key(adaptationId)).orElse(null);
        guard.requireTask(adaptationId, task);
        if (task != null) {
            task = instances.cancel(task.id());
        }
        return view(adaptationId, task, true);
    }

    private static ReaderAdaptationDispatchView view(UUID adaptationId, TaskInstanceView task, boolean cancelled) {
        return new ReaderAdaptationDispatchView(adaptationId, task == null ? null : task.id(), cancelled,
                task == null ? null : task.status().name());
    }
}
