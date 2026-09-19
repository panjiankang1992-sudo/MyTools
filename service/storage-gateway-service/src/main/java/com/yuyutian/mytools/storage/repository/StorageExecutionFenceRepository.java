package com.yuyutian.mytools.storage.repository;

import com.yuyutian.mytools.storage.model.TaskExecutionFence;
import com.yuyutian.mytools.storage.service.ExecutionFenceConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 存储副作用任务执行隔离仓储。
 */
@Repository
public class StorageExecutionFenceRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建执行隔离仓储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public StorageExecutionFenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 原子取得或推进业务键对应的执行令牌。
     *
     * @param fence 执行隔离凭据
     */
    public void acquire(TaskExecutionFence fence) {
        if (!fence.valid()) {
            throw new IllegalArgumentException(com.yuyutian.mytools.storage.model.ErrorCode.OPERATION_STATE_INVALID.code());
        }
        if (update(fence) == 1) {
            return;
        }
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM storage_execution_fence WHERE business_key=?", Integer.class,
                fence.businessKey());
        if (existing != null && existing > 0) {
            throw new ExecutionFenceConflictException();
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO storage_execution_fence
                        (business_key,task_instance_id,step_name,fencing_token,updated_at)
                    VALUES (?,?,?,?,?)
                    """, fence.businessKey(), fence.taskInstanceId().toString(), fence.stepName(),
                    fence.fencingToken(), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException exception) {
            // 并发首次写入由唯一键裁决，失败请求必须重新比较令牌。
            if (update(fence) != 1) {
                throw new ExecutionFenceConflictException();
            }
        }
    }

    private int update(TaskExecutionFence fence) {
        return jdbcTemplate.update("""
                UPDATE storage_execution_fence
                SET task_instance_id=?, step_name=?, fencing_token=?, updated_at=?
                WHERE business_key=? AND (fencing_token<? OR
                    (fencing_token=? AND task_instance_id=? AND step_name=?))
                """, fence.taskInstanceId().toString(), fence.stepName(), fence.fencingToken(),
                Timestamp.from(Instant.now()), fence.businessKey(), fence.fencingToken(), fence.fencingToken(),
                fence.taskInstanceId().toString(), fence.stepName());
    }
}
