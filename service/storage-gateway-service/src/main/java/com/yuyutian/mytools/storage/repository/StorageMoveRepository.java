package com.yuyutian.mytools.storage.repository;

import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.StorageMoveState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 远端移动状态机仓储。
 */
@Repository
public class StorageMoveRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建移动状态仓储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public StorageMoveRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等创建移动初始状态。
     *
     * @param operationId 操作标识
     */
    public void initialize(UUID operationId) {
        Instant now = Instant.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO storage_move_state
                        (operation_id, phase, remote_job_id, desired_terminal_status, failure_code,
                         recovery_action, recovery_required, created_at, updated_at)
                    VALUES (?, 'READY', NULL, NULL, NULL, NULL, FALSE, ?, ?)
                    """, operationId.toString(), Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // 幂等重放保留已有状态。
        }
    }

    /**
     * 为移动目标建立服务内排他写入栅栏。
     *
     * @param operationId 操作标识
     * @param providerId 目标 Provider 标识
     * @param targetPath 目标路径
     * @param pathSha256 目标路径摘要
     */
    @Transactional
    public void reserveTarget(UUID operationId, UUID providerId, String targetPath, String pathSha256) {
        jdbcTemplate.queryForObject("SELECT id FROM storage_provider WHERE id = ? FOR UPDATE",
                String.class, providerId.toString());
        var reservations = jdbcTemplate.query("""
                SELECT operation_id, target_path FROM storage_move_target_reservation
                WHERE target_provider_id = ?
                """, (resultSet, rowNumber) -> new TargetReservation(
                UUID.fromString(resultSet.getString("operation_id")), resultSet.getString("target_path")),
                providerId.toString());
        boolean conflict = reservations.stream().anyMatch(reservation -> !reservation.operationId().equals(operationId)
                && pathsOverlap(reservation.path(), targetPath));
        if (conflict) {
            throw new IllegalStateException(ErrorCode.TARGET_CONFLICT.code());
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO storage_move_target_reservation
                        (operation_id, target_provider_id, target_path, target_path_sha256, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, operationId.toString(), providerId.toString(), targetPath, pathSha256,
                    Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException exception) {
            Integer owned = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM storage_move_target_reservation
                    WHERE operation_id = ? AND target_provider_id = ? AND target_path_sha256 = ?
                    """, Integer.class, operationId.toString(), providerId.toString(), pathSha256);
            if (owned == null || owned != 1) {
                throw new IllegalStateException(ErrorCode.TARGET_CONFLICT.code(), exception);
            }
        }
    }

    /**
     * 释放移动目标写入栅栏。
     *
     * @param operationId 操作标识
     */
    public void releaseTarget(UUID operationId) {
        jdbcTemplate.update("DELETE FROM storage_move_target_reservation WHERE operation_id = ?",
                operationId.toString());
    }

    /**
     * 查询移动状态。
     *
     * @param operationId 操作标识
     * @return 状态
     */
    public StorageMoveState require(UUID operationId) {
        return find(operationId).orElseThrow(
                () -> new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code()));
    }

    /**
     * 条件推进阶段并绑定后台任务。
     *
     * @param operationId 操作标识
     * @param expectedPhase 预期阶段
     * @param targetPhase 目标阶段
     * @param jobId 后台任务标识
     * @param failureCode 可选原始失败码
     * @return 是否推进成功
     */
    public boolean transition(UUID operationId, String expectedPhase, String targetPhase, Long jobId,
                              String failureCode) {
        return jdbcTemplate.update("""
                UPDATE storage_move_state
                SET phase = ?, remote_job_id = ?, failure_code = COALESCE(?, failure_code), updated_at = ?
                WHERE operation_id = ? AND phase = ?
                """, targetPhase, jobId, failureCode, Timestamp.from(Instant.now()), operationId.toString(),
                expectedPhase) == 1;
    }

    /**
     * 设置中止期望终态。
     *
     * @param operationId 操作标识
     * @param status 期望终态
     */
    public void requestAbort(UUID operationId, String status) {
        jdbcTemplate.update("""
                UPDATE storage_move_state
                SET desired_terminal_status = COALESCE(desired_terminal_status, ?), updated_at = ?
                WHERE operation_id = ? AND phase <> 'TERMINAL'
                """, status, Timestamp.from(Instant.now()), operationId.toString());
    }

    /**
     * 清除已经通过前向重试恢复的暂态错误。
     *
     * @param operationId 操作标识
     */
    public void clearFailure(UUID operationId) {
        jdbcTemplate.update("UPDATE storage_move_state SET failure_code = NULL, updated_at = ? WHERE operation_id = ?",
                Timestamp.from(Instant.now()), operationId.toString());
    }

    /**
     * 标记需要人工或恢复任务继续收敛。
     *
     * @param operationId 操作标识
     * @param failureCode 恢复错误码
     * @param recoveryAction 恢复动作
     */
    public void markRecoveryRequired(UUID operationId, String failureCode, String recoveryAction) {
        jdbcTemplate.update("""
                UPDATE storage_move_state
                SET phase = 'RECOVERY_REQUIRED', remote_job_id = NULL, recovery_required = TRUE,
                    failure_code = ?, recovery_action = ?, updated_at = ?
                WHERE operation_id = ? AND phase <> 'TERMINAL'
                """, failureCode, recoveryAction, Timestamp.from(Instant.now()), operationId.toString());
    }

    /**
     * 启动恢复清理任务。
     *
     * @param operationId 操作标识
     * @param jobId 后台任务标识
     * @return 是否启动成功
     */
    public boolean startRecovery(UUID operationId, long jobId) {
        return jdbcTemplate.update("""
                UPDATE storage_move_state SET phase = 'RECOVERING', remote_job_id = ?, updated_at = ?
                WHERE operation_id = ? AND phase = 'RECOVERY_REQUIRED'
                """, jobId, Timestamp.from(Instant.now()), operationId.toString()) == 1;
    }

    /**
     * 完成恢复清理并保留原业务失败终态。
     *
     * @param operationId 操作标识
     */
    public void completeRecovery(UUID operationId) {
        jdbcTemplate.update("""
                UPDATE storage_move_state SET phase = 'RECOVERED', remote_job_id = NULL,
                    recovery_required = FALSE, updated_at = ?
                WHERE operation_id = ? AND phase = 'RECOVERING'
                """, Timestamp.from(Instant.now()), operationId.toString());
    }

    /**
     * 将失败的恢复任务重新排队。
     *
     * @param operationId 操作标识
     */
    public void retryRecovery(UUID operationId) {
        jdbcTemplate.update("""
                UPDATE storage_move_state SET phase = 'RECOVERY_REQUIRED', remote_job_id = NULL, updated_at = ?
                WHERE operation_id = ? AND phase = 'RECOVERING'
                """, Timestamp.from(Instant.now()), operationId.toString());
    }

    private Optional<StorageMoveState> find(UUID operationId) {
        return jdbcTemplate.query("SELECT * FROM storage_move_state WHERE operation_id = ?",
                (resultSet, rowNumber) -> new StorageMoveState(
                        UUID.fromString(resultSet.getString("operation_id")), resultSet.getString("phase"),
                        resultSet.getObject("remote_job_id", Long.class),
                        resultSet.getString("desired_terminal_status"), resultSet.getString("failure_code"),
                        resultSet.getString("recovery_action"), resultSet.getBoolean("recovery_required"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()), operationId.toString())
                .stream().findFirst();
    }

    private boolean pathsOverlap(String first, String second) {
        return first.isEmpty() || second.isEmpty() || first.equals(second)
                || first.startsWith(second + "/") || second.startsWith(first + "/");
    }

    private record TargetReservation(UUID operationId, String path) {
    }
}
